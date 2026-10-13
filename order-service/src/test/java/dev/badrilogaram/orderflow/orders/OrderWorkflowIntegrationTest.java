package dev.badrilogaram.orderflow.orders;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the actual HTTP API, transactions and dispatcher against a local payment boundary. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:order-workflow-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
    "orderflow.worker.enabled=false", "orderflow.request-timeout=1s",
    "orderflow.lease-duration=5s", "orderflow.retry-backoff=10ms", "orderflow.max-attempts=3"
})
@Timeout(30)
class OrderWorkflowIntegrationTest {
    private static final PaymentStub payments = new PaymentStub();
    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentDispatcher dispatcher;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("orderflow.payment-base-url", payments::baseUrl);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM payment_command");
        jdbc.update("DELETE FROM order_line");
        jdbc.update("DELETE FROM orders");
        jdbc.update("UPDATE product SET available_stock=100 WHERE sku='BOOK'");
        jdbc.update("UPDATE product SET available_stock=20 WHERE sku='KEYBOARD'");
        jdbc.update("UPDATE product SET available_stock=10 WHERE sku='HEADSET'");
        payments.reset();
    }

    @AfterAll static void stopPayments() { payments.close(); }

    @Test
    void approvedOrderUsesCatalogPricesAndCompletesOneDurableCommand() throws Exception {
        var created = create("approved", request("APPROVE", 2));
        assertEquals(201, created.statusCode(), created.body());
        Models.OrderView order = read(created);
        assertEquals("PAYMENT_PENDING", order.status());
        assertEquals(5000, order.totalMinor());
        assertEquals("USD", order.currency());
        assertEquals(98, stock("BOOK"));
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_line"));
        assertEquals(1, count("payment_command"));
        assertEquals("/api/orders/" + order.id(), created.headers().firstValue("Location").orElseThrow());

        assertEquals(1, dispatcher.dispatchOnce());
        Models.OrderView paid = details(order.id());
        assertEquals("CONFIRMED", paid.status());
        assertEquals("DONE", paid.outboxStatus());
        assertEquals(1, paid.paymentAttempts());
        assertNotNull(paid.paymentId());
        assertEquals(0, dispatcher.dispatchOnce());
        assertEquals(1, payments.calls.get());
        assertEquals(1, payments.results.size());
        assertEquals(0, payments.invalidRequests.get());
        assertEquals(98, stock("BOOK"));
    }

    @Test
    void idempotentReplayReturnsOriginalOrderAndRejectsChangedPayload() throws Exception {
        Models.OrderView original = read(create("same-key", request("APPROVE", 1)));
        var replay = create("same-key", request("APPROVE", 1));
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(original.id(), read(replay).id());
        assertEquals(409, create("same-key", request("APPROVE", 2)).statusCode());
        assertEquals(1, count("orders"));
        assertEquals(1, count("payment_command"));
        assertEquals(99, stock("BOOK"));
    }

    @Test
    void simultaneousDuplicateRequestsReserveStockOnlyOnce() throws Exception {
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> requests = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) requests.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return create("concurrent-same", request("APPROVE", 1));
            }));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            UUID expectedId = null;
            int creations = 0;
            for (var future : requests) {
                var response = future.get();
                assertTrue(response.statusCode() == 200 || response.statusCode() == 201, response.body());
                if (response.statusCode() == 201) creations++;
                UUID id = read(response).id();
                if (expectedId == null) expectedId = id;
                assertEquals(expectedId, id);
            }
            assertEquals(1, creations);
        }
        assertEquals(1, count("orders"));
        assertEquals(1, count("payment_command"));
        assertEquals(99, stock("BOOK"));
    }

    @Test
    void twoBuyersCannotBothReserveTheLastItem() throws Exception {
        jdbc.update("UPDATE product SET available_stock=1 WHERE sku='BOOK'");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> { start.await(); return create("buyer-one", request("APPROVE", 1)); });
            var second = pool.submit(() -> { start.await(); return create("buyer-two", request("APPROVE", 1)); });
            start.countDown();
            var statuses = List.of(first.get().statusCode(), second.get().statusCode()).stream().sorted().toList();
            assertEquals(List.of(201, 409), statuses);
        }
        assertEquals(0, stock("BOOK"));
        assertEquals(1, count("orders"));
        assertEquals(1, count("payment_command"));
    }

    @Test
    void unavailableSecondLineRollsBackOrderFirstReservationAndOutbox() throws Exception {
        jdbc.update("UPDATE product SET available_stock=1 WHERE sku='KEYBOARD'");
        String body = """
            {"customerReference":"demo","items":[{"sku":"BOOK","quantity":1},
             {"sku":"KEYBOARD","quantity":2}],"paymentScenario":"APPROVE"}
            """;
        assertEquals(409, create("atomic-failure", body).statusCode());
        assertEquals(100, stock("BOOK"));
        assertEquals(1, stock("KEYBOARD"));
        assertEquals(0, count("orders"));
        assertEquals(0, count("order_line"));
        assertEquals(0, count("payment_command"));
        assertEquals(0, payments.calls.get());
    }

    @Test
    void declineReturnsInventoryExactlyOnceAndCannotBeManuallyRetried() throws Exception {
        Models.OrderView order = read(create("declined", request("DECLINE", 3)));
        assertEquals(97, stock("BOOK"));
        assertEquals(1, dispatcher.dispatchOnce());
        Models.OrderView declined = details(order.id());
        assertEquals("PAYMENT_DECLINED", declined.status());
        assertEquals("DONE", declined.outboxStatus());
        assertEquals(100, stock("BOOK"));
        assertEquals(0, dispatcher.dispatchOnce());
        assertEquals(409, retry(order.id()).statusCode());
        assertEquals(100, stock("BOOK"));
        assertEquals(1, payments.calls.get());
    }

    @Test
    void transientFailureRetriesAndEventuallyConfirms() throws Exception {
        Models.OrderView order = read(create("transient", request("RETRY_ONCE", 1)));
        dispatcher.dispatchOnce();
        Models.OrderView waiting = details(order.id());
        assertEquals("PAYMENT_PENDING", waiting.status());
        assertEquals("READY", waiting.outboxStatus());
        assertEquals(1, waiting.paymentAttempts());
        assertEquals(99, stock("BOOK"));
        makeDue(order.id());
        dispatcher.dispatchOnce();
        assertEquals("CONFIRMED", details(order.id()).status());
        assertEquals(2, details(order.id()).paymentAttempts());
        assertEquals(2, payments.calls.get());
        assertEquals(1, payments.results.size());
    }

    @Test
    void committedPaymentWithLostResponseIsRecoveredUsingSameProviderKey() throws Exception {
        Models.OrderView order = read(create("lost-response", request("LOST_RESPONSE", 1)));
        dispatcher.dispatchOnce();
        assertEquals("PAYMENT_PENDING", details(order.id()).status());
        assertEquals(1, payments.results.size(), "Provider has already committed the payment");
        UUID existingPayment = payments.results.get(order.id().toString()).paymentId();
        makeDue(order.id());
        dispatcher.dispatchOnce();
        Models.OrderView confirmed = details(order.id());
        assertEquals("CONFIRMED", confirmed.status());
        assertEquals(existingPayment, confirmed.paymentId());
        assertEquals(1, payments.results.size(), "Retry must reuse the payment, never charge again");
        assertEquals(2, payments.calls.get());
        assertEquals(99, stock("BOOK"));
    }

    @Test
    void exhaustionHoldsStockAndManualRecoveryKeepsOriginalProviderIdentity() throws Exception {
        Models.OrderView order = read(create("exhausted", request("ALWAYS_UNAVAILABLE", 1)));
        for (int attempt = 0; attempt < 3; attempt++) {
            makeDue(order.id());
            assertEquals(1, dispatcher.dispatchOnce());
        }
        Models.OrderView review = details(order.id());
        assertEquals("PAYMENT_REVIEW", review.status());
        assertEquals("DEAD", review.outboxStatus());
        assertEquals(3, review.paymentAttempts());
        assertEquals(99, stock("BOOK"), "Uncertain payment outcome must keep its reservation");
        assertEquals(0, dispatcher.dispatchOnce());
        payments.recovered = true;
        var retried = retry(order.id());
        assertEquals(202, retried.statusCode(), retried.body());
        dispatcher.dispatchOnce();
        assertEquals("CONFIRMED", details(order.id()).status());
        assertEquals(1, payments.keys.size());
        assertTrue(payments.keys.containsKey(order.id().toString()));
        assertEquals(99, stock("BOOK"));
    }

    @Test
    void expiredClaimIsRecoveredAfterWorkerDisappears() throws Exception {
        Models.OrderView order = read(create("expired-lease", request("APPROVE", 1)));
        jdbc.update("UPDATE payment_command SET status='PROCESSING', attempts=1, lease_until=?, lease_token=? WHERE order_id=?",
            System.currentTimeMillis() - 1000, UUID.randomUUID().toString(), order.id().toString());
        assertEquals(1, dispatcher.dispatchOnce());
        assertEquals("CONFIRMED", details(order.id()).status());
        assertEquals(2, details(order.id()).paymentAttempts());
        assertEquals(99, stock("BOOK"));
        assertEquals(1, payments.results.size());
    }

    @Test
    void unexpiredClaimIsNotSentAgain() throws Exception {
        Models.OrderView order = read(create("live-lease", request("APPROVE", 1)));
        jdbc.update("UPDATE payment_command SET status='PROCESSING', attempts=1, lease_until=?, lease_token=? WHERE order_id=?",
            System.currentTimeMillis() + 60000, UUID.randomUUID().toString(), order.id().toString());
        assertEquals(0, dispatcher.dispatchOnce());
        assertEquals(0, payments.calls.get());
        assertEquals("PAYMENT_PENDING", details(order.id()).status());
    }

    @Test
    void exhaustedExpiredClaimRequiresReviewWithoutCallingProviderAgain() throws Exception {
        Models.OrderView order = read(create("exhausted-expired-lease", request("APPROVE", 1)));
        jdbc.update("UPDATE payment_command SET status='PROCESSING', attempts=3, lease_until=?, lease_token=? WHERE order_id=?",
            System.currentTimeMillis() - 1000, UUID.randomUUID().toString(), order.id().toString());
        assertEquals(0, dispatcher.dispatchOnce());
        Models.OrderView review = details(order.id());
        assertEquals("PAYMENT_REVIEW", review.status());
        assertEquals("DEAD", review.outboxStatus());
        assertEquals(99, stock("BOOK"));
        assertEquals(0, payments.calls.get());
        assertEquals(0, dispatcher.dispatchOnce());
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "DECLINE"})
    void formerLeaseOwnerCannotCommitOrRestockAfterAnotherWorkerTakesOwnership(String scenario) throws Exception {
        Models.OrderView order = read(create("stale-owner", request(scenario, 1)));
        payments.entered = new CountDownLatch(1);
        payments.release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var staleWorker = pool.submit(() -> dispatcher.dispatchOnce());
            try {
                assertTrue(payments.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                // Simulate a successor's new lease while the former owner still has a response in flight.
                jdbc.update("UPDATE payment_command SET lease_token=?, lease_until=? WHERE order_id=?",
                    UUID.randomUUID().toString(), System.currentTimeMillis() + 60000, order.id().toString());
            } finally { payments.release.countDown(); }
            staleWorker.get();
        }
        assertEquals("PAYMENT_PENDING", details(order.id()).status(), "A stale completion must be fenced out");
        assertEquals("PROCESSING", details(order.id()).outboxStatus());
        assertEquals(99, stock("BOOK"));
        jdbc.update("UPDATE payment_command SET lease_until=0 WHERE order_id=?", order.id().toString());
        dispatcher.dispatchOnce();
        assertEquals(scenario.equals("DECLINE") ? "PAYMENT_DECLINED" : "CONFIRMED", details(order.id()).status());
        assertEquals(scenario.equals("DECLINE") ? 100 : 99, stock("BOOK"));
        assertEquals(1, payments.results.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDER_ID", "AMOUNT", "STATUS", "MALFORMED_JSON"})
    void invalidSuccessfulProviderResponseRequiresReviewAndKeepsStock(String fault) throws Exception {
        Models.OrderView order = read(create("invalid-provider-response", request("APPROVE", 1)));
        payments.responseFault = fault;
        assertEquals(1, dispatcher.dispatchOnce());
        Models.OrderView review = details(order.id());
        assertEquals("PAYMENT_REVIEW", review.status());
        assertEquals("DEAD", review.outboxStatus());
        assertNull(review.paymentId());
        assertEquals(1, review.paymentAttempts());
        assertEquals(99, stock("BOOK"));
        assertFalse(review.lastError().isBlank());
        assertEquals(0, dispatcher.dispatchOnce());
        assertEquals(1, payments.calls.get(), "An invalid provider reply must not be retried automatically");
    }

    @Test
    void apiRejectsUnauthenticatedAndInvalidInputsWithoutSideEffects() throws Exception {
        assertEquals(401, call("GET", "/api/products", null, null, false).statusCode());
        assertEquals(401, call("GET", "/actuator/metrics", null, null, false).statusCode());
        assertEquals(200, call("GET", "/actuator/health", null, null, false).statusCode());
        assertEquals(200, call("GET", "/api/products", null, null, true).statusCode());
        assertEquals(400, create(null, request("APPROVE", 1)).statusCode());
        assertEquals(400, create("bad key", request("APPROVE", 1)).statusCode());
        assertEquals(400, create("bad-quantity", request("APPROVE", 0)).statusCode());
        assertEquals(400, create("fractional-quantity", request("APPROVE", 1).replace("\"quantity\":1", "\"quantity\":1.5")).statusCode());
        assertEquals(400, create("null-item", "{\"customerReference\":\"demo\",\"items\":[null],\"paymentScenario\":\"APPROVE\"}").statusCode());
        assertEquals(400, create("malformed", "{broken").statusCode());
        assertEquals(404, call("GET", "/api/orders/" + UUID.randomUUID(), null, null, true).statusCode());
        assertEquals(0, count("orders"));
        assertEquals(100, stock("BOOK"));
    }

    @Test
    void pendingAndConfirmedOrdersRejectManualRetry() throws Exception {
        Models.OrderView order = read(create("retry-conflict", request("APPROVE", 1)));
        assertEquals(409, retry(order.id()).statusCode());
        dispatcher.dispatchOnce();
        assertEquals(409, retry(order.id()).statusCode());
        assertEquals(1, payments.calls.get());
    }

    private HttpResponse<String> create(String key, String body) throws Exception {
        return call("POST", "/api/orders", body, key, true);
    }
    private HttpResponse<String> retry(UUID id) throws Exception {
        return call("POST", "/api/orders/" + id + "/retry-payment", null, null, true);
    }
    private Models.OrderView read(HttpResponse<String> response) {
        return json.readValue(response.body(), Models.OrderView.class);
    }
    private Models.OrderView details(UUID id) throws Exception {
        var result = call("GET", "/api/orders/" + id, null, null, true);
        assertEquals(200, result.statusCode(), result.body());
        return read(result);
    }
    private void makeDue(UUID id) { jdbc.update("UPDATE payment_command SET next_attempt_at=0 WHERE order_id=?", id.toString()); }
    private int stock(String sku) { return jdbc.queryForObject("SELECT available_stock FROM product WHERE sku=?", Integer.class, sku); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private HttpResponse<String> call(String method, String path, String body, String key, boolean authorized) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (authorized) request.header("X-API-Key", "local-order-key");
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String request(String scenario, int quantity) {
        return "{\"customerReference\":\"demo\",\"items\":[{\"sku\":\"BOOK\",\"quantity\":" + quantity
            + "}],\"paymentScenario\":\"" + scenario + "\"}";
    }

    private static final class PaymentStub implements AutoCloseable {
        private final JsonMapper mapper = JsonMapper.builder().build();
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger invalidRequests = new AtomicInteger();
        final ConcurrentHashMap<String, AtomicInteger> keys = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Models.PaymentResponse> results = new ConcurrentHashMap<>();
        volatile boolean recovered;
        volatile String responseFault;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;

        PaymentStub() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(executor);
                server.createContext("/payments", this::handle);
                server.start();
            } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
        }
        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void reset() {
            calls.set(0); invalidRequests.set(0); keys.clear(); results.clear(); recovered = false;
            entered = null; release = null;
            responseFault = null;
        }
        private void handle(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            try {
                Models.PaymentRequest request = mapper.readValue(exchange.getRequestBody().readAllBytes(), Models.PaymentRequest.class);
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                if (!exchange.getRequestMethod().equals("POST") || !"local-payment-key".equals(exchange.getRequestHeaders().getFirst("X-API-Key"))
                        || !request.orderId().toString().equals(key) || request.amountMinor() <= 0 || !"USD".equals(request.currency())) {
                    invalidRequests.incrementAndGet();
                    respond(exchange, 400, "{}");
                    return;
                }
                int attempt = keys.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
                if (!recovered && (request.scenario() == Models.Scenario.ALWAYS_UNAVAILABLE
                        || request.scenario() == Models.Scenario.RETRY_ONCE && attempt == 1)) {
                    respond(exchange, 503, "{\"detail\":\"simulated provider unavailable\"}");
                    return;
                }
                Models.PaymentResponse result = results.computeIfAbsent(key, ignored -> new Models.PaymentResponse(
                    UUID.randomUUID(), request.orderId(), request.scenario() == Models.Scenario.DECLINE ? "DECLINED" : "CAPTURED",
                    request.amountMinor(), request.currency()));
                if (entered != null && release != null) {
                    entered.countDown();
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Stub response gate timed out");
                }
                if (request.scenario() == Models.Scenario.LOST_RESPONSE && attempt == 1) {
                    // Persisted result exists but the caller receives no usable response.
                    respond(exchange, 503, "{\"detail\":\"response lost after commit\"}");
                    return;
                }
                if ("MALFORMED_JSON".equals(responseFault)) {
                    respond(exchange, 200, "{incomplete");
                    return;
                }
                var body = new java.util.LinkedHashMap<String, Object>();
                body.put("paymentId", result.paymentId());
                body.put("orderId", "ORDER_ID".equals(responseFault) ? UUID.randomUUID() : result.orderId());
                body.put("status", "STATUS".equals(responseFault) ? "UNKNOWN_OUTCOME" : result.status());
                body.put("amountMinor", "AMOUNT".equals(responseFault) ? result.amountMinor() + 1 : result.amountMinor());
                body.put("currency", result.currency());
                // These diagnostic fields are part of the simulator's response, but are not required by the client.
                body.put("requestAttempts", attempt);
                body.put("captureCount", result.status().equals("CAPTURED") ? 1 : 0);
                respond(exchange, 200, mapper.writeValueAsString(body));
            } catch (Exception failure) {
                invalidRequests.incrementAndGet();
                respond(exchange, 500, "{}");
            } finally { exchange.close(); }
        }
        private void respond(HttpExchange exchange, int status, String content) throws IOException {
            byte[] body = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}

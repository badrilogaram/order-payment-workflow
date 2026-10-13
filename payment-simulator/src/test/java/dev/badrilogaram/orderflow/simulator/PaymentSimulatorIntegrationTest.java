package dev.badrilogaram.orderflow.simulator;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:simulator-integration;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "payment-simulator.api-key=test-payment-key"
})
@Timeout(30)
class PaymentSimulatorIntegrationTest {
    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void replayReturnsTheSamePaymentAndOnlyOneCapture() throws Exception {
        UUID order = UUID.randomUUID();
        String key = key();
        String body = body(order, Scenario.APPROVE);
        PaymentView first = payment(post(key, body), 200);
        PaymentView second = payment(post(key, body), 200);
        assertEquals(first.paymentId(), second.paymentId());
        assertEquals(order, second.orderId());
        assertEquals(1250, second.amountMinor());
        assertEquals("USD", second.currency());
        assertEquals("CAPTURED", second.status());
        assertEquals(2, second.requestAttempts());
        assertEquals(1, second.captureCount());
        assertEquals(second, find(order));
    }

    @Test
    void concurrentIdenticalRequestsProduceOnePaymentAndOneCapture() throws Exception {
        UUID order = UUID.randomUUID();
        String key = key();
        String body = body(order, Scenario.APPROVE);
        List<HttpResponse<String>> responses = concurrently(12, index -> post(key, body));
        UUID paymentId = payment(responses.getFirst(), 200).paymentId();
        for (var response : responses) {
            PaymentView result = payment(response, 200);
            assertEquals(paymentId, result.paymentId());
            assertEquals("CAPTURED", result.status());
            assertEquals(1, result.captureCount());
        }
        PaymentView stored = find(order);
        assertEquals(12, stored.requestAttempts());
        assertEquals(1, stored.captureCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM simulated_payment WHERE order_id = ?",
                Integer.class, order.toString()));
    }

    @Test
    void concurrentDifferentKeysForOneOrderAllowOnlyOnePayment() throws Exception {
        UUID order = UUID.randomUUID();
        String body = body(order, Scenario.APPROVE);
        List<HttpResponse<String>> results = concurrently(2, index -> post(key(), body));
        assertEquals(List.of(200, 409), results.stream().map(HttpResponse::statusCode).sorted().toList());
        PaymentView stored = find(order);
        assertEquals(1, stored.requestAttempts());
        assertEquals(1, stored.captureCount());
    }

    @Test
    void reusingAKeyWithChangedMoneyScenarioOrOrderIsAConflict() throws Exception {
        UUID order = UUID.randomUUID();
        String key = key();
        String original = body(order, Scenario.APPROVE);
        PaymentView accepted = payment(post(key, original), 200);
        assertEquals(409, post(key, original.replace("1250", "1251")).statusCode());
        assertEquals(409, post(key, body(order, Scenario.DECLINE)).statusCode());
        assertEquals(409, post(key, body(UUID.randomUUID(), Scenario.APPROVE)).statusCode());
        assertEquals(accepted, find(order));
    }

    @Test
    void anotherKeyCannotCaptureAnAlreadyPaidOrder() throws Exception {
        UUID order = UUID.randomUUID();
        String body = body(order, Scenario.APPROVE);
        PaymentView original = payment(post(key(), body), 200);
        assertEquals(409, post(key(), body).statusCode());
        assertEquals(original, find(order));
    }

    @Test
    void declineIsABusinessOutcomeAndRemainsDeclinedOnReplay() throws Exception {
        UUID order = UUID.randomUUID();
        String key = key();
        String body = body(order, Scenario.DECLINE);
        PaymentView first = payment(post(key, body), 200);
        PaymentView replay = payment(post(key, body), 200);
        assertEquals("DECLINED", first.status());
        assertEquals(first.paymentId(), replay.paymentId());
        assertEquals("DECLINED", replay.status());
        assertEquals(0, replay.captureCount());
        assertEquals(2, replay.requestAttempts());
    }

    @Test
    void retryOnceCommitsPendingAttemptBeforeReturning503ThenCapturesOnce() throws Exception {
        UUID order = UUID.randomUUID();
        String key = key();
        String body = body(order, Scenario.RETRY_ONCE);
        HttpResponse<String> unavailable = post(key, body);
        assertEquals(503, unavailable.statusCode(), unavailable.body());
        assertEquals("1", unavailable.headers().firstValue("Retry-After").orElseThrow());
        PaymentView pending = find(order);
        assertEquals("PENDING", pending.status());
        assertEquals(1, pending.requestAttempts());
        assertEquals(0, pending.captureCount());
        PaymentView captured = payment(post(key, body), 200);
        assertEquals(pending.paymentId(), captured.paymentId());
        assertEquals("CAPTURED", captured.status());
        assertEquals(2, captured.requestAttempts());
        PaymentView replay = payment(post(key, body), 200);
        assertEquals(1, replay.captureCount());
        assertEquals(3, replay.requestAttempts());
    }

    @Test
    void lostResponseCommitsCaptureBefore503AndReplayDoesNotCaptureAgain() throws Exception {
        UUID order = UUID.randomUUID();
        String key = key();
        String body = body(order, Scenario.LOST_RESPONSE);
        assertEquals(503, post(key, body).statusCode());
        PaymentView stored = find(order);
        assertEquals("CAPTURED", stored.status());
        assertEquals(1, stored.requestAttempts());
        assertEquals(1, stored.captureCount());
        PaymentView replay = payment(post(key, body), 200);
        assertEquals(stored.paymentId(), replay.paymentId());
        assertEquals("CAPTURED", replay.status());
        assertEquals(2, replay.requestAttempts());
        assertEquals(1, replay.captureCount());
    }

    @Test
    void alwaysUnavailablePersistsAttemptsWithoutACapture() throws Exception {
        UUID order = UUID.randomUUID();
        String key = key();
        String body = body(order, Scenario.ALWAYS_UNAVAILABLE);
        for (int i = 0; i < 3; i++) assertEquals(503, post(key, body).statusCode());
        PaymentView stored = find(order);
        assertEquals("PENDING", stored.status());
        assertEquals(3, stored.requestAttempts());
        assertEquals(0, stored.captureCount());
    }

    @Test
    void missingOrWrongCredentialsCannotReadOrCreatePayments() throws Exception {
        UUID order = UUID.randomUUID();
        assertEquals(401, call("POST", "/payments", key(), body(order, Scenario.APPROVE), null).statusCode());
        assertEquals(401, call("POST", "/payments", key(), body(order, Scenario.APPROVE), "wrong").statusCode());
        assertEquals(401, call("GET", "/payments/" + order, null, null, null).statusCode());
        assertEquals(401, call("GET", "/actuator/metrics", null, null, null).statusCode());
        assertEquals(200, call("GET", "/actuator/health", null, null, null).statusCode());
        assertEquals(404, call("GET", "/payments/" + order, null, null, "test-payment-key").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "bad/key", "key@domain", "has,comma"})
    void invalidIdempotencyKeysAreRejected(String key) throws Exception {
        UUID order = UUID.randomUUID();
        assertEquals(400, post(key, body(order, Scenario.APPROVE)).statusCode());
        assertEquals(404, call("GET", "/payments/" + order, null, null, "test-payment-key").statusCode());
    }

    @Test
    void missingAndTooLongIdempotencyKeysAreRejected() throws Exception {
        String body = body(UUID.randomUUID(), Scenario.APPROVE);
        assertEquals(400, post(null, body).statusCode());
        assertEquals(400, post("x".repeat(129), body).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{broken", "{}",
            "{\"orderId\":\"not-a-uuid\",\"amountMinor\":1250,\"currency\":\"USD\",\"scenario\":\"APPROVE\"}",
            "{\"orderId\":\"ORDER\",\"amountMinor\":0,\"currency\":\"USD\",\"scenario\":\"APPROVE\"}",
            "{\"orderId\":\"ORDER\",\"amountMinor\":-1,\"currency\":\"USD\",\"scenario\":\"APPROVE\"}",
            "{\"orderId\":\"ORDER\",\"amountMinor\":12.50,\"currency\":\"USD\",\"scenario\":\"APPROVE\"}",
            "{\"orderId\":\"ORDER\",\"amountMinor\":9223372036854775808,\"currency\":\"USD\",\"scenario\":\"APPROVE\"}",
            "{\"orderId\":\"ORDER\",\"amountMinor\":1250,\"currency\":\"EUR\",\"scenario\":\"APPROVE\"}",
            "{\"orderId\":\"ORDER\",\"amountMinor\":1250,\"currency\":\"USD\",\"scenario\":\"UNKNOWN\"}",
            "{\"orderId\":\"ORDER\",\"amountMinor\":1250,\"currency\":\"USD\",\"scenario\":null}"
    })
    void malformedRequestsDoNotCreateAPayment(String body) throws Exception {
        UUID order = UUID.randomUUID();
        HttpResponse<String> response = post(key(), body.replace("ORDER", order.toString()));
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("application/problem+json"));
        assertEquals(404, call("GET", "/payments/" + order, null, null, "test-payment-key").statusCode());
    }

    @Test
    void malformedLookupIdIs400AndUnknownPaymentIs404() throws Exception {
        assertEquals(400, call("GET", "/payments/invalid", null, null, "test-payment-key").statusCode());
        assertEquals(404, call("GET", "/payments/" + UUID.randomUUID(), null, null, "test-payment-key").statusCode());
    }

    private PaymentView find(UUID order) throws Exception {
        return payment(call("GET", "/payments/" + order, null, null, "test-payment-key"), 200);
    }

    private PaymentView payment(HttpResponse<String> response, int expectedStatus) {
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return json.readValue(response.body(), PaymentView.class);
    }

    private HttpResponse<String> post(String key, String body) throws Exception {
        return call("POST", "/payments", key, body, "test-payment-key");
    }

    private HttpResponse<String> call(String method, String path, String key, String body, String apiKey) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15));
        if (key != null) request.header("Idempotency-Key", key);
        if (apiKey != null) request.header("X-API-Key", apiKey);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String key() { return "test-" + UUID.randomUUID(); }

    private String body(UUID order, Scenario scenario) {
        return json.writeValueAsString(new CreatePaymentRequest(order, 1250, "USD", scenario));
    }

    private List<HttpResponse<String>> concurrently(int count, RequestTask task) throws Exception {
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent requests did not start");
                    return task.call(index);
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            var responses = new ArrayList<HttpResponse<String>>();
            for (var future : futures) responses.add(future.get(20, TimeUnit.SECONDS));
            return responses;
        }
    }

    @FunctionalInterface
    private interface RequestTask { HttpResponse<String> call(int index) throws Exception; }
}

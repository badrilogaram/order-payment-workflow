package dev.badrilogaram.orderflow.orders;

import static dev.badrilogaram.orderflow.orders.Models.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OrderService {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final OrderProperties properties;
    private final JsonMapper json;

    public OrderService(JdbcTemplate db, PlatformTransactionManager manager, OrderProperties properties, JsonMapper json) {
        this.db = db; this.transaction = new TransactionTemplate(manager); this.properties = properties; this.json = json;
    }

    public Created create(String key, CreateOrder request) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,120}"))
            throw problem(HttpStatus.BAD_REQUEST, "Supply an Idempotency-Key of 1-120 letters, digits, dots, underscores, colons or hyphens");
        List<ItemInput> items = request.items().stream().sorted(Comparator.comparing(ItemInput::sku)).toList();
        if (items.stream().map(ItemInput::sku).distinct().count() != items.size())
            throw problem(HttpStatus.BAD_REQUEST, "Each SKU must appear once");
        Scenario scenario = request.paymentScenario() == null ? Scenario.APPROVE : request.paymentScenario();
        String customer = request.customerReference().strip();
        String hash = fingerprint(new CreateOrder(customer, items, scenario));
        try {
            return transaction.execute(tx -> {
                Optional<Created> previous = replay(key, hash);
                if (previous.isPresent()) return previous.get();
                List<Line> lines = new ArrayList<>();
                long total = 0;
                for (ItemInput item : items) {
                    Product product = product(item.sku());
                    long subtotal = Math.multiplyExact(product.priceMinor(), item.quantity());
                    total = Math.addExact(total, subtotal);
                    lines.add(new Line(item.sku(), item.quantity(), product.priceMinor(), subtotal));
                }
                UUID id = UUID.randomUUID();
                long now = System.currentTimeMillis();
                // The unique request key is acquired before changing inventory.
                db.update("""
                    INSERT INTO orders(id,idempotency_key,request_hash,customer_ref,status,currency,total_minor,scenario,created_at,updated_at)
                    VALUES (?,?,?,?,'PAYMENT_PENDING','USD',?,?,?,?)
                    """, id.toString(), key, hash, customer, total, scenario.name(), now, now);
                // Consistent SKU ordering avoids locking the same products in opposite orders.
                for (Line line : lines) {
                    int reserved = db.update("UPDATE product SET available_stock=available_stock-? WHERE sku=? AND available_stock>=?",
                            line.quantity(), line.sku(), line.quantity());
                    if (reserved != 1) throw problem(HttpStatus.CONFLICT, "Insufficient stock for " + line.sku());
                    db.update("INSERT INTO order_line(order_id,sku,quantity,unit_price_minor,line_total_minor) VALUES (?,?,?,?,?)",
                            id.toString(), line.sku(), line.quantity(), line.unitPriceMinor(), line.lineTotalMinor());
                }
                db.update("INSERT INTO payment_command(id,order_id,status,next_attempt_at) VALUES (?,?,'READY',?)",
                        id.toString(), id.toString(), now);
                return new Created(get(id), false);
            });
        } catch (DuplicateKeyException concurrentRequest) {
            // A competing transaction may have committed this key. Read only after our transaction rolled back.
            return replay(key, hash).orElseThrow(() -> concurrentRequest);
        }
    }

    private Optional<Created> replay(String key, String hash) {
        var records = db.query("SELECT id,request_hash FROM orders WHERE idempotency_key=?", (r, n) ->
                Map.entry(r.getString("id"), r.getString("request_hash")), key);
        if (records.isEmpty()) return Optional.empty();
        if (!records.getFirst().getValue().equals(hash))
            throw problem(HttpStatus.CONFLICT, "Idempotency-Key was already used with different order details");
        return Optional.of(new Created(get(UUID.fromString(records.getFirst().getKey())), true));
    }

    public List<Product> products() {
        return db.query("SELECT * FROM product ORDER BY sku", (r, n) -> new Product(r.getString("sku"), r.getString("name"),
                r.getLong("price_minor"), r.getString("currency"), r.getInt("available_stock")));
    }

    private Product product(String sku) {
        return products().stream().filter(p -> p.sku().equals(sku)).findFirst()
                .orElseThrow(() -> problem(HttpStatus.BAD_REQUEST, "Unknown SKU: " + sku));
    }

    public OrderView get(UUID id) {
        var rows = db.query("""
            SELECT o.*,c.attempts,c.status AS command_status,c.next_attempt_at,c.last_error
            FROM orders o JOIN payment_command c ON c.order_id=o.id WHERE o.id=?
            """, (r, n) -> new OrderView(UUID.fromString(r.getString("id")), r.getString("customer_ref"), r.getString("status"),
                r.getString("currency"), r.getLong("total_minor"), Instant.ofEpochMilli(r.getLong("created_at")),
                Instant.ofEpochMilli(r.getLong("updated_at")), uuid(r.getString("payment_id")), r.getString("last_error"),
                r.getInt("attempts"), r.getString("command_status"), "READY".equals(r.getString("command_status"))
                    ? Instant.ofEpochMilli(r.getLong("next_attempt_at")) : null, lines(id)), id.toString());
        if (rows.isEmpty()) throw problem(HttpStatus.NOT_FOUND, "Order not found");
        return rows.getFirst();
    }

    private List<Line> lines(UUID id) {
        return db.query("SELECT * FROM order_line WHERE order_id=? ORDER BY sku", (r, n) ->
                new Line(r.getString("sku"), r.getInt("quantity"), r.getLong("unit_price_minor"), r.getLong("line_total_minor")), id.toString());
    }

    public List<OrderView> recent() {
        return db.queryForList("SELECT id FROM orders ORDER BY created_at DESC,id DESC LIMIT 20", String.class)
                .stream().map(id -> get(UUID.fromString(id))).toList();
    }

    public OrderView retry(UUID id) {
        return transaction.execute(tx -> {
            int changed = db.update("""
                UPDATE payment_command SET status='READY',attempts=0,next_attempt_at=?,lease_until=NULL,lease_token=NULL,last_error=''
                WHERE order_id=? AND status='DEAD'
                """, System.currentTimeMillis(), id.toString());
            if (changed == 0) {
                get(id);
                throw problem(HttpStatus.CONFLICT, "Only a payment awaiting review can be retried");
            }
            db.update("UPDATE orders SET status='PAYMENT_PENDING',updated_at=? WHERE id=?", System.currentTimeMillis(), id.toString());
            return get(id);
        });
    }

    Optional<Claim> claim() {
        return transaction.execute(tx -> {
            long now = System.currentTimeMillis();
            var candidates = db.queryForList("""
                SELECT order_id FROM payment_command
                WHERE (status='READY' AND next_attempt_at<=?) OR (status='PROCESSING' AND lease_until<=?)
                ORDER BY next_attempt_at,order_id LIMIT 10
                """, String.class, now, now);
            for (String orderId : candidates) {
                String token = UUID.randomUUID().toString();
                int claimed = db.update("""
                    UPDATE payment_command SET status='PROCESSING',attempts=attempts+1,lease_token=?,lease_until=?
                    WHERE order_id=? AND ((status='READY' AND next_attempt_at<=?) OR (status='PROCESSING' AND lease_until<=?))
                    """, token, now + properties.leaseDuration().toMillis(), orderId, now, now);
                if (claimed == 0) continue;
                Claim claim = db.queryForObject("""
                    SELECT o.id,o.total_minor,o.currency,o.scenario,c.attempts FROM orders o
                    JOIN payment_command c ON c.order_id=o.id WHERE o.id=?
                    """, (r, n) -> new Claim(UUID.fromString(r.getString("id")), token, r.getInt("attempts"),
                        r.getLong("total_minor"), r.getString("currency"), Scenario.valueOf(r.getString("scenario"))), orderId);
                if (claim.attempts() > properties.maxAttempts()) {
                    failure(claim, "Previous worker stopped and the automatic attempt budget is exhausted", false);
                    continue;
                }
                return Optional.of(claim);
            }
            return Optional.empty();
        });
    }

    boolean complete(Claim claim, PaymentResponse payment) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            int owned = db.update("""
                UPDATE payment_command SET status='DONE',lease_token=NULL,lease_until=NULL,last_error=''
                WHERE order_id=? AND status='PROCESSING' AND lease_token=? AND lease_until>?
                """, claim.orderId().toString(), claim.token(), System.currentTimeMillis());
            if (owned == 0) return false;
            boolean declined = payment.status().equals("DECLINED");
            if (declined) {
                for (Line line : lines(claim.orderId())) db.update("UPDATE product SET available_stock=available_stock+? WHERE sku=?",
                        line.quantity(), line.sku());
            }
            db.update("UPDATE orders SET status=?,payment_id=?,updated_at=? WHERE id=?",
                    declined ? "PAYMENT_DECLINED" : "CONFIRMED", payment.paymentId().toString(), System.currentTimeMillis(), claim.orderId().toString());
            return true;
        }));
    }

    boolean failure(Claim claim, String message, boolean retryable) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            boolean exhausted = !retryable || claim.attempts() >= properties.maxAttempts();
            long delay = Math.min(60_000, properties.retryBackoff().toMillis() * (1L << Math.min(claim.attempts()-1, 10)));
            int owned = db.update("""
                UPDATE payment_command SET status=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,last_error=?
                WHERE order_id=? AND status='PROCESSING' AND lease_token=? AND lease_until>?
                """, exhausted ? "DEAD" : "READY", System.currentTimeMillis()+delay, message,
                    claim.orderId().toString(), claim.token(), System.currentTimeMillis());
            if (owned == 0) return false;
            if (exhausted) db.update("UPDATE orders SET status='PAYMENT_REVIEW',updated_at=? WHERE id=?",
                    System.currentTimeMillis(), claim.orderId().toString());
            return true;
        }));
    }

    private String fingerprint(CreateOrder request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static ResponseStatusException problem(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}

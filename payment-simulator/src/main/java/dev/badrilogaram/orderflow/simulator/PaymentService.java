package dev.badrilogaram.orderflow.simulator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** A simulator: captureCount is a persisted counter, never a real financial transaction. */
@Service
public class PaymentService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PaymentService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public Decision submit(String idempotencyKey, CreatePaymentRequest request) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain 1 to 128 letters, digits, dots, underscores, colons or hyphens");
        }
        String hash = fingerprint(request);
        try {
            return transactions.execute(status -> apply(idempotencyKey, request, hash));
        } catch (DuplicateKeyException collision) {
            // A concurrent insert may win. Its transaction has committed before the unique
            // constraint reports failure. Retry only after our failed transaction is rolled back.
            List<String> keys = jdbc.queryForList("SELECT idempotency_key FROM simulated_payment WHERE order_id = ?",
                    String.class, request.orderId().toString());
            if (!keys.isEmpty() && !keys.getFirst().equals(idempotencyKey)) {
                throw conflict("This order already has a payment under another idempotency key");
            }
            return transactions.execute(status -> apply(idempotencyKey, request, hash));
        }
    }

    public PaymentView find(UUID orderId) {
        List<PaymentView> results = jdbc.query("SELECT * FROM simulated_payment WHERE order_id = ?",
                (rs, row) -> view(rs), orderId.toString());
        if (results.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        }
        return results.getFirst();
    }

    private Decision apply(String key, CreatePaymentRequest request, String requestHash) {
        List<StoredPayment> existing = jdbc.query(
                "SELECT * FROM simulated_payment WHERE idempotency_key = ? FOR UPDATE",
                (rs, row) -> new StoredPayment(rs.getString("request_hash"), view(rs)), key);
        PaymentView payment;
        if (existing.isEmpty()) {
            payment = next(request, UUID.randomUUID(), 1);
            jdbc.update("""
                    INSERT INTO simulated_payment
                    (idempotency_key, request_hash, payment_id, order_id, amount_minor, currency,
                     scenario, status, request_attempts, capture_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, key, requestHash, payment.paymentId().toString(), request.orderId().toString(),
                    request.amountMinor(), request.currency(), request.scenario().name(), payment.status(),
                    payment.requestAttempts(), payment.captureCount());
        } else {
            StoredPayment stored = existing.getFirst();
            if (!stored.requestHash().equals(requestHash)) {
                throw conflict("Idempotency-Key was already used for a different payment request");
            }
            payment = next(request, stored.payment().paymentId(), stored.payment().requestAttempts() + 1);
            jdbc.update("""
                    UPDATE simulated_payment SET status = ?, request_attempts = ?, capture_count = ?
                    WHERE idempotency_key = ?
                    """, payment.status(), payment.requestAttempts(), payment.captureCount(), key);
        }
        boolean unavailable = payment.status().equals("PENDING")
                || (request.scenario() == Scenario.LOST_RESPONSE && payment.requestAttempts() == 1);
        // Returning a decision lets the transaction commit even when HTTP will be 503.
        return new Decision(payment, unavailable);
    }

    private PaymentView next(CreatePaymentRequest request, UUID paymentId, int attempt) {
        String status = switch (request.scenario()) {
            case DECLINE -> "DECLINED";
            case ALWAYS_UNAVAILABLE -> "PENDING";
            case RETRY_ONCE -> attempt == 1 ? "PENDING" : "CAPTURED";
            case APPROVE, LOST_RESPONSE -> "CAPTURED";
        };
        return new PaymentView(paymentId, request.orderId(), status, request.amountMinor(),
                request.currency(), attempt, status.equals("CAPTURED") ? 1 : 0);
    }

    private PaymentView view(ResultSet rs) throws SQLException {
        return new PaymentView(UUID.fromString(rs.getString("payment_id")),
                UUID.fromString(rs.getString("order_id")), rs.getString("status"),
                rs.getLong("amount_minor"), rs.getString("currency"),
                rs.getInt("request_attempts"), rs.getInt("capture_count"));
    }

    private String fingerprint(CreatePaymentRequest request) {
        String canonical = request.orderId() + "|" + request.amountMinor() + "|"
                + request.currency() + "|" + request.scenario();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256", impossible);
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record Decision(PaymentView payment, boolean unavailable) { }
    private record StoredPayment(String requestHash, PaymentView payment) { }
}

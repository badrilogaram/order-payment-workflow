package dev.badrilogaram.orderflow.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Models {
    private Models() { }
    public enum Scenario { APPROVE, DECLINE, RETRY_ONCE, LOST_RESPONSE, ALWAYS_UNAVAILABLE }
    public record ItemInput(@NotBlank @Pattern(regexp="[A-Z0-9-]{1,40}") String sku,
                            @Min(1) @Max(10) int quantity) { }
    public record CreateOrder(@NotBlank @Size(max=80) String customerReference,
                              @NotEmpty @Size(max=20) List<@NotNull @Valid ItemInput> items, Scenario paymentScenario) { }
    public record Product(String sku, String name, long priceMinor, String currency, int availableStock) { }
    public record Line(String sku, int quantity, long unitPriceMinor, long lineTotalMinor) { }
    public record OrderView(UUID id, String customerReference, String status, String currency, long totalMinor,
            Instant createdAt, Instant updatedAt, UUID paymentId, String lastError, int paymentAttempts,
            String outboxStatus, Instant nextAttemptAt, List<Line> items) { }
    public record Created(OrderView order, boolean replay) { }
    public record Claim(UUID orderId, String token, int attempts, long amountMinor, String currency, Scenario scenario) { }
    public record PaymentRequest(UUID orderId, long amountMinor, String currency, Scenario scenario) { }
    public record PaymentResponse(UUID paymentId, UUID orderId, String status, long amountMinor, String currency) { }
}

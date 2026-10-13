package dev.badrilogaram.orderflow.simulator;

import java.util.UUID;

public record PaymentView(UUID paymentId, UUID orderId, String status, long amountMinor,
                          String currency, int requestAttempts, int captureCount) { }

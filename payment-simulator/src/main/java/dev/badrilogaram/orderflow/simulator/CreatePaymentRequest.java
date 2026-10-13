package dev.badrilogaram.orderflow.simulator;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record CreatePaymentRequest(@NotNull UUID orderId, @Positive long amountMinor,
                                   @NotNull @Pattern(regexp = "USD") String currency,
                                   @NotNull Scenario scenario) { }

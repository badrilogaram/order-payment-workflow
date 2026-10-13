package dev.badrilogaram.orderflow.simulator;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("payment-simulator")
public record SimulatorProperties(@NotBlank String apiKey) { }

package dev.badrilogaram.orderflow.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("orderflow")
public record OrderProperties(@NotBlank String apiKey, @NotNull URI paymentBaseUrl,
        @NotBlank String paymentApiKey, @NotNull Duration requestTimeout, @Min(1) @Max(10) int maxAttempts,
        @NotNull Duration retryBackoff, @NotNull Duration leaseDuration, @Valid @NotNull Worker worker) {
    public OrderProperties {
        if (paymentBaseUrl != null && (!("http".equals(paymentBaseUrl.getScheme()) || "https".equals(paymentBaseUrl.getScheme()))
                || paymentBaseUrl.getHost() == null || paymentBaseUrl.getUserInfo() != null
                || paymentBaseUrl.getQuery() != null || paymentBaseUrl.getFragment() != null
                || !(paymentBaseUrl.getPath().isEmpty() || paymentBaseUrl.getPath().equals("/"))))
            throw new IllegalArgumentException("Payment destination must be an absolute HTTP(S) origin");
        if (requestTimeout != null && (requestTimeout.toMillis() < 1 || requestTimeout.compareTo(Duration.ofSeconds(10)) > 0))
            throw new IllegalArgumentException("Request timeout must be 1 ms to 10 seconds");
        if (retryBackoff != null && (retryBackoff.toMillis() < 1 || retryBackoff.compareTo(Duration.ofSeconds(60)) > 0))
            throw new IllegalArgumentException("Retry backoff must be 1 ms to 60 seconds");
        if (leaseDuration != null && requestTimeout != null && (leaseDuration.compareTo(requestTimeout.plusSeconds(1)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0))
            throw new IllegalArgumentException("Lease must exceed request timeout by one second and be at most five minutes");
    }
    public record Worker(boolean enabled, @Min(20) @Max(60000) long pollDelayMs) { }
}

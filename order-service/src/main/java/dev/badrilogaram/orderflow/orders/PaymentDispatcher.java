package dev.badrilogaram.orderflow.orders;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentDispatcher {
    private static final Logger log = LoggerFactory.getLogger(PaymentDispatcher.class);
    private final OrderService orders;
    private final PaymentClient payments;
    private final OrderProperties properties;
    private final MeterRegistry metrics;
    public PaymentDispatcher(OrderService orders, PaymentClient payments, OrderProperties properties, MeterRegistry metrics) {
        this.orders = orders; this.payments = payments; this.properties = properties; this.metrics = metrics;
    }
    @Scheduled(fixedDelayString="${orderflow.worker.poll-delay-ms:250}")
    public void scheduled() {
        if (!properties.worker().enabled()) return;
        try { dispatchOnce(); }
        catch (Exception failed) { log.warn("Payment dispatch stopped; leased work remains recoverable: {}", failed.getClass().getSimpleName()); }
    }
    public int dispatchOnce() {
        var available = orders.claim();
        if (available.isEmpty()) return 0;
        var claim = available.get();
        try {
            // The claim transaction has committed. Never hold database locks during a provider call.
            var result = payments.pay(claim);
            if (orders.complete(claim, result)) {
                metrics.counter("orderflow.payment.attempts", "outcome", result.status().toLowerCase()).increment();
                log.info("Payment completed orderId={} outcome={} attempt={}", claim.orderId(), result.status(), claim.attempts());
            }
        } catch (PaymentClient.GatewayFailure failure) {
            if (orders.failure(claim, failure.getMessage(), failure.retryable())) {
                metrics.counter("orderflow.payment.attempts", "outcome", "unavailable").increment();
                log.info("Payment attempt unresolved orderId={} attempt={} retryable={}", claim.orderId(), claim.attempts(), failure.retryable());
            }
        } catch (InterruptedException interrupted) {
            // Leave the durable lease intact; another worker can recover it after expiry.
            Thread.currentThread().interrupt();
        }
        return 1;
    }
}

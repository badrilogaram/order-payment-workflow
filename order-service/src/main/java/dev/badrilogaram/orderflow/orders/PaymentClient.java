package dev.badrilogaram.orderflow.orders;

import static dev.badrilogaram.orderflow.orders.Models.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentClient {
    private final OrderProperties properties;
    private final GatewayTransport http;
    private final JsonMapper json;
    public PaymentClient(OrderProperties properties, GatewayTransport http, JsonMapper json) {
        this.properties = properties; this.http = http; this.json = json;
    }
    public PaymentResponse pay(Claim claim) throws GatewayFailure, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(properties.paymentBaseUrl().toString().replaceAll("/$", "") + "/payments"))
                .timeout(properties.requestTimeout()).header("Content-Type", "application/json")
                .header("X-API-Key", properties.paymentApiKey()).header("Idempotency-Key", claim.orderId().toString())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        new PaymentRequest(claim.orderId(), claim.amountMinor(), claim.currency(), claim.scenario())))).build();
        GatewayTransport.Response response;
        try { response = http.send(request, 16_000); }
        catch (IOException failure) { throw new GatewayFailure("Payment response unavailable: " + failure.getClass().getSimpleName(), true); }
        if (response.status() != 200) throw new GatewayFailure("Payment provider returned HTTP " + response.status(),
                response.status() == 408 || response.status() == 429 || response.status() >= 500);
        PaymentResponse payment;
        try { payment = json.readValue(response.body(), PaymentResponse.class); }
        catch (RuntimeException invalid) { throw new GatewayFailure("Payment provider returned an invalid response", false); }
        if (payment == null || payment.paymentId() == null || !claim.orderId().equals(payment.orderId())
                || payment.amountMinor() != claim.amountMinor() || !claim.currency().equals(payment.currency())
                || !("CAPTURED".equals(payment.status()) || "DECLINED".equals(payment.status())))
            throw new GatewayFailure("Payment response identity, amount, currency or outcome did not match", false);
        return payment;
    }
    public static final class GatewayFailure extends Exception {
        private final boolean retryable;
        GatewayFailure(String message, boolean retryable) { super(message); this.retryable = retryable; }
        public boolean retryable() { return retryable; }
    }
}

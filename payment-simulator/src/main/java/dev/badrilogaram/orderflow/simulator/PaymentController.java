package dev.badrilogaram.orderflow.simulator;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {
    private final PaymentService payments;

    public PaymentController(PaymentService payments) { this.payments = payments; }

    @PostMapping
    public ResponseEntity<?> submit(@RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody CreatePaymentRequest request) {
        PaymentService.Decision decision = payments.submit(key, request);
        if (decision.unavailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1")
                    .body(new Unavailable("SIMULATED_UNAVAILABLE", "Retry using the same Idempotency-Key"));
        }
        return ResponseEntity.ok(decision.payment());
    }

    @GetMapping("/{orderId}")
    public PaymentView find(@PathVariable UUID orderId) { return payments.find(orderId); }

    private record Unavailable(String code, String message) { }
}

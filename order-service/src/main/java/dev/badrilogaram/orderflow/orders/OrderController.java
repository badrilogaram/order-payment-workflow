package dev.badrilogaram.orderflow.orders;

import static dev.badrilogaram.orderflow.orders.Models.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class OrderController {
    private final OrderService orders;
    public OrderController(OrderService orders) { this.orders = orders; }
    @PostMapping("/orders")
    public ResponseEntity<OrderView> create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateOrder request) {
        Created result = orders.create(key, request);
        return ResponseEntity.status(result.replay() ? 200 : 201).location(URI.create("/api/orders/" + result.order().id()))
                .header("Idempotency-Replayed", Boolean.toString(result.replay())).body(result.order());
    }
    @GetMapping("/products") public List<Product> products() { return orders.products(); }
    @GetMapping("/orders") public List<OrderView> recent() { return orders.recent(); }
    @GetMapping("/orders/{id}") public OrderView get(@PathVariable UUID id) { return orders.get(id); }
    @PostMapping("/orders/{id}/retry-payment")
    public ResponseEntity<OrderView> retry(@PathVariable UUID id) { return ResponseEntity.accepted().body(orders.retry(id)); }
}

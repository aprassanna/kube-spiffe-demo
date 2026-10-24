package dev.example.spiffedemo.checkout.api;

import dev.example.spiffedemo.checkout.api.OrderApi.CreateOrderRequest;
import dev.example.spiffedemo.checkout.api.OrderApi.OrderResponse;
import dev.example.spiffedemo.checkout.security.SpiffeAuthorizationFilter;
import dev.example.spiffedemo.checkout.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request,
                                                HttpServletRequest httpRequest) {
        String caller = (String) httpRequest.getAttribute(SpiffeAuthorizationFilter.CALLER_ATTRIBUTE);
        OrderResponse order = orders.placeOrder(request, caller);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + order.orderId())).body(order);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> get(@PathVariable String orderId) {
        return orders.find(orderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

package dev.example.spiffedemo.checkout.service;

import dev.example.spiffedemo.checkout.api.OrderApi.CreateOrderRequest;
import dev.example.spiffedemo.checkout.api.OrderApi.OrderResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** In-memory order store. A real checkout would call a payment and inventory system here. */
@Service
public class OrderService {

    private final Map<String, OrderResponse> orders = new ConcurrentHashMap<>();

    public OrderResponse placeOrder(CreateOrderRequest request, String placedBy) {
        long totalCents = request.items().stream()
                .mapToLong(item -> item.quantity() * item.unitPriceCents())
                .sum();
        OrderResponse order = new OrderResponse(
                "ord_" + UUID.randomUUID(),
                request.cartId(),
                totalCents,
                "PLACED",
                placedBy,
                Instant.now());
        orders.put(order.orderId(), order);
        return order;
    }

    public Optional<OrderResponse> find(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }
}

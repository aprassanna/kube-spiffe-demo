package dev.example.spiffedemo.checkout.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;

/** Request and response types for the checkout REST API. See docs/openapi/checkout-service.openapi.yaml. */
public final class OrderApi {

    private OrderApi() {
    }

    public record CreateOrderRequest(@NotBlank String cartId, @NotEmpty List<@Valid LineItem> items) {
    }

    public record LineItem(@NotBlank String sku, @Min(1) int quantity, @Min(0) long unitPriceCents) {
    }

    public record OrderResponse(
            String orderId,
            String cartId,
            long totalCents,
            String status,
            String placedBy,
            Instant createdAt) {
    }
}

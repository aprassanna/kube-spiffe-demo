package dev.example.spiffedemo.cart.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;

/** Request and response types for the cart REST API. See docs/openapi/cart-service.openapi.yaml. */
public final class CartApi {

    private CartApi() {
    }

    public record AddItemRequest(@NotBlank String sku, @Min(1) int quantity, @Min(0) long unitPriceCents) {
    }

    public record CartItem(String sku, int quantity, long unitPriceCents) {
    }

    public record CartResponse(String cartId, List<CartItem> items, long totalCents) {

        public static CartResponse of(String cartId, List<CartItem> items) {
            long total = items.stream().mapToLong(item -> item.quantity() * item.unitPriceCents()).sum();
            return new CartResponse(cartId, items, total);
        }
    }

    /** Body sent to checkout-service. Field names match its OrderApi.CreateOrderRequest. */
    public record CheckoutOrderRequest(String cartId, List<CartItem> items) {
    }

    /** Body returned by checkout-service. */
    public record CheckoutOrderResponse(
            String orderId,
            String cartId,
            long totalCents,
            String status,
            String placedBy,
            Instant createdAt) {
    }
}

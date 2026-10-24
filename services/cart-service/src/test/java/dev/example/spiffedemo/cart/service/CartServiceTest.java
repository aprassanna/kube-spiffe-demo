package dev.example.spiffedemo.cart.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.example.spiffedemo.cart.api.CartApi.CartItem;
import dev.example.spiffedemo.cart.api.CartApi.CartResponse;
import org.junit.jupiter.api.Test;

class CartServiceTest {

    @Test
    void itemsAccumulatePerCart() {
        CartService service = new CartService();

        service.addItem("c1", new CartItem("sku-1", 2, 500));
        service.addItem("c1", new CartItem("sku-2", 1, 1000));

        assertThat(service.items("c1")).hasSize(2);
        assertThat(service.items("other")).isEmpty();
    }

    @Test
    void clearRemovesCart() {
        CartService service = new CartService();
        service.addItem("c1", new CartItem("sku-1", 1, 100));

        service.clear("c1");

        assertThat(service.items("c1")).isEmpty();
    }

    @Test
    void totalIsSumOfQuantityTimesUnitPrice() {
        CartResponse cart = CartResponse.of("c1", java.util.List.of(
                new CartItem("sku-1", 2, 500),
                new CartItem("sku-2", 1, 1000)));

        assertThat(cart.totalCents()).isEqualTo(2000);
    }
}

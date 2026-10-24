package dev.example.spiffedemo.cart.service;

import dev.example.spiffedemo.cart.api.CartApi.CartItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** In-memory cart store. Carts are created on first use. */
@Service
public class CartService {

    private final Map<String, List<CartItem>> carts = new ConcurrentHashMap<>();

    public List<CartItem> items(String cartId) {
        return List.copyOf(carts.getOrDefault(cartId, List.of()));
    }

    public List<CartItem> addItem(String cartId, CartItem item) {
        carts.compute(cartId, (id, existing) -> {
            List<CartItem> updated = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            updated.add(item);
            return updated;
        });
        return items(cartId);
    }

    public void clear(String cartId) {
        carts.remove(cartId);
    }
}

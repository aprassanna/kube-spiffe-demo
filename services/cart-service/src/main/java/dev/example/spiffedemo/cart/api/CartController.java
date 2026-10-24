package dev.example.spiffedemo.cart.api;

import dev.example.spiffedemo.cart.api.CartApi.AddItemRequest;
import dev.example.spiffedemo.cart.api.CartApi.CartItem;
import dev.example.spiffedemo.cart.api.CartApi.CartResponse;
import dev.example.spiffedemo.cart.api.CartApi.CheckoutOrderResponse;
import dev.example.spiffedemo.cart.service.CartException;
import dev.example.spiffedemo.cart.service.CartService;
import dev.example.spiffedemo.cart.service.CheckoutClient;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/carts/{cartId}")
public class CartController {

    private final CartService carts;
    private final CheckoutClient checkout;

    public CartController(CartService carts, CheckoutClient checkout) {
        this.carts = carts;
        this.checkout = checkout;
    }

    @GetMapping
    public CartResponse getCart(@PathVariable String cartId) {
        return CartResponse.of(cartId, carts.items(cartId));
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CartResponse addItem(@PathVariable String cartId, @Valid @RequestBody AddItemRequest request) {
        List<CartItem> items = carts.addItem(cartId, new CartItem(request.sku(), request.quantity(), request.unitPriceCents()));
        return CartResponse.of(cartId, items);
    }

    /**
     * Sends the cart to checkout-service. The outbound call carries this workload's JWT-SVID,
     * so checkout can verify that the request came from the cart-service SPIFFE identity.
     */
    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutOrderResponse checkout(@PathVariable String cartId) {
        List<CartItem> items = carts.items(cartId);
        if (items.isEmpty()) {
            throw CartException.conflict("Cart " + cartId + " is empty");
        }
        CheckoutOrderResponse order = checkout.placeOrder(cartId, items);
        carts.clear(cartId);
        return order;
    }
}

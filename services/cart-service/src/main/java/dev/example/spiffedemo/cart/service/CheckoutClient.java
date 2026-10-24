package dev.example.spiffedemo.cart.service;

import dev.example.spiffedemo.cart.api.CartApi.CartItem;
import dev.example.spiffedemo.cart.api.CartApi.CheckoutOrderRequest;
import dev.example.spiffedemo.cart.api.CartApi.CheckoutOrderResponse;
import io.spiffe.exception.JwtSvidException;
import io.spiffe.workloadapi.JwtSource;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls checkout-service over REST. Each call carries a fresh JWT-SVID issued by SPIRE to this
 * workload's SPIFFE ID, for the checkout audience.
 */
@Component
public class CheckoutClient {

    private final RestClient restClient;
    private final JwtSource jwtSource;
    private final String audience;

    public CheckoutClient(RestClient.Builder builder,
                          JwtSource jwtSource,
                          @Value("${checkout.base-url}") String baseUrl,
                          @Value("${checkout.audience}") String audience) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.jwtSource = jwtSource;
        this.audience = audience;
    }

    public CheckoutOrderResponse placeOrder(String cartId, List<CartItem> items) {
        String token = fetchJwtSvid();
        try {
            return restClient.post()
                    .uri("/api/v1/orders")
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CheckoutOrderRequest(cartId, items))
                    .retrieve()
                    .body(CheckoutOrderResponse.class);
        } catch (RestClientException e) {
            throw new CartException(HttpStatus.BAD_GATEWAY, "checkout-service did not accept the order", e);
        }
    }

    private String fetchJwtSvid() {
        try {
            return jwtSource.fetchJwtSvid(audience).getToken();
        } catch (JwtSvidException e) {
            throw new CartException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No JWT-SVID available from the SPIRE agent for audience " + audience, e);
        }
    }
}

package dev.example.spiffedemo.cart.service;

import dev.example.spiffedemo.cart.api.CartApi.CartItem;
import dev.example.spiffedemo.cart.api.CartApi.CheckoutOrderRequest;
import dev.example.spiffedemo.cart.api.CartApi.CheckoutOrderResponse;
import dev.example.spiffedemo.cart.tls.SvidRotation;
import dev.example.spiffedemo.cart.tls.SvidSnapshot;
import dev.example.spiffedemo.cart.tls.TlsMaterial;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls checkout-service over mutual TLS. The client presents this workload's X.509-SVID and
 * accepts the server only if its SPIFFE ID is the expected checkout identity. The RestClient is
 * rebuilt whenever SPIRE rotates the SVID.
 */
@Component
public class CheckoutClient {

    private static final Logger log = LoggerFactory.getLogger(CheckoutClient.class);

    private final SvidRotation svid;
    private final String baseUrl;
    private final Set<String> expectedServerIds;
    private final AtomicReference<ClientCache> cache = new AtomicReference<>();

    public CheckoutClient(SvidRotation svid,
                          @Value("${checkout.base-url}") String baseUrl,
                          @Value("${checkout.expected-server-id}") String expectedServerId) {
        this.svid = svid;
        this.baseUrl = baseUrl;
        this.expectedServerIds = Set.of(expectedServerId);
    }

    public CheckoutOrderResponse placeOrder(String cartId, List<CartItem> items) {
        try {
            return restClient().post()
                    .uri("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CheckoutOrderRequest(cartId, items))
                    .retrieve()
                    .body(CheckoutOrderResponse.class);
        } catch (RestClientException e) {
            log.warn("checkout call failed: {}", e.getMessage());
            throw new CartException(HttpStatus.BAD_GATEWAY, "checkout-service did not accept the mTLS call", e);
        }
    }

    private RestClient restClient() {
        SvidSnapshot snapshot = svid.current();
        ClientCache cached = cache.get();
        if (cached != null && cached.snapshot() == snapshot) {
            return cached.client();
        }
        try {
            SSLContext context = TlsMaterial.clientContext(snapshot, expectedServerIds);
            // The server is identified by its SPIFFE ID (ServerIdentityTrustManager), not by DNS name.
            // The X.509-SVID has no DNS SAN, so the hostname check is replaced by that identity check.
            CloseableHttpClient http = HttpClients.custom()
                    .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                            .setTlsSocketStrategy(new DefaultClientTlsStrategy(context, (host, session) -> true))
                            .build())
                    .build();
            RestClient client = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(new HttpComponentsClientHttpRequestFactory(http))
                    .build();
            cache.set(new ClientCache(snapshot, client));
            return client;
        } catch (GeneralSecurityException | IOException e) {
            throw new CartException(HttpStatus.SERVICE_UNAVAILABLE, "Could not build the mTLS client from the SVID", e);
        }
    }

    private record ClientCache(SvidSnapshot snapshot, RestClient client) {
    }
}

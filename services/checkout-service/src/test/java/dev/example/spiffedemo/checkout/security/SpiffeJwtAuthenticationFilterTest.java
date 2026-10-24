package dev.example.spiffedemo.checkout.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.spiffe.workloadapi.JwtSource;
import jakarta.servlet.FilterChain;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SpiffeJwtAuthenticationFilterTest {

    private final JwtSource jwtSource = mock(JwtSource.class);
    private final SpiffeJwtAuthenticationFilter filter = new SpiffeJwtAuthenticationFilter(
            jwtSource, "checkout-service", Set.of("spiffe://example.org/ns/cart/sa/cart-service"));

    @Test
    void rejectsApiRequestWithoutBearerToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void rejectsNonBearerAuthorizationHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void leavesActuatorEndpointsUnauthenticated() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }
}

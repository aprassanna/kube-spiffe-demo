package dev.example.spiffedemo.checkout.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SpiffeAuthorizationFilterTest {

    private static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private final SpiffeAuthorizationFilter filter = new SpiffeAuthorizationFilter(
            Set.of("spiffe://example.org/ns/cart/sa/cart-service"));

    @Test
    void allowsCallerWhoseSpiffeIdIsOnTheAllowList() throws Exception {
        MockHttpServletRequest request = orderRequest(certificate("cart-svid.pem"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(request.getAttribute(SpiffeAuthorizationFilter.CALLER_ATTRIBUTE))
                .isEqualTo("spiffe://example.org/ns/cart/sa/cart-service");
    }

    @Test
    void rejectsValidSpiffeIdThatIsNotOnTheAllowList() throws Exception {
        MockHttpServletRequest request = orderRequest(certificate("rogue-svid.pem"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(chain);
    }

    @Test
    void rejectsRequestWithoutClientCertificate() throws Exception {
        MockHttpServletRequest request = orderRequest(null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void leavesActuatorAlone() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health"))).isTrue();
    }

    private static MockHttpServletRequest orderRequest(X509Certificate certificate) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        if (certificate != null) {
            request.setAttribute(CERTIFICATE_ATTRIBUTE, new X509Certificate[] {certificate});
        }
        return request;
    }

    private static X509Certificate certificate(String resource) throws IOException {
        try (InputStream in = SpiffeAuthorizationFilterTest.class.getResourceAsStream("/" + resource)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (java.security.cert.CertificateException e) {
            throw new IOException(e);
        }
    }
}

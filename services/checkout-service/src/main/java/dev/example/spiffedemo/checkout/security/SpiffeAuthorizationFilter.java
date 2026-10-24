package dev.example.spiffedemo.checkout.security;

import dev.example.spiffedemo.checkout.tls.SpiffeIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorizes /api/** requests using the client certificate that Tomcat verified during the TLS
 * handshake (the connector requires a client certificate). The caller's SPIFFE ID is read from
 * the certificate's URI SAN and checked against the allow-list.
 *
 * <ul>
 *   <li>No client certificate or no SPIFFE ID: 401.</li>
 *   <li>SPIFFE ID not on the allow-list: 403.</li>
 *   <li>Otherwise the SPIFFE ID is stored as a request attribute and the request proceeds.</li>
 * </ul>
 */
@Component
public class SpiffeAuthorizationFilter extends OncePerRequestFilter {

    public static final String CALLER_ATTRIBUTE = "spiffe.caller";

    private static final Logger log = LoggerFactory.getLogger(SpiffeAuthorizationFilter.class);
    private static final String CLIENT_CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private final Set<String> allowedCallers;

    public SpiffeAuthorizationFilter(@Value("${spiffe.allowed-callers}") Set<String> allowedCallers) {
        this.allowedCallers = Set.copyOf(allowedCallers);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> caller = clientSpiffeId(request);
        if (caller.isEmpty()) {
            reject(response, HttpStatus.UNAUTHORIZED, "A client X.509-SVID with a SPIFFE ID is required");
            return;
        }
        if (!allowedCallers.contains(caller.get())) {
            log.warn("Rejected {} {}: caller {} is not on the allow-list",
                    request.getMethod(), request.getRequestURI(), caller.get());
            reject(response, HttpStatus.FORBIDDEN, "The calling workload is not authorized");
            return;
        }
        log.info("Authenticated caller {} via mTLS for {} {}", caller.get(), request.getMethod(), request.getRequestURI());
        request.setAttribute(CALLER_ATTRIBUTE, caller.get());
        chain.doFilter(request, response);
    }

    private static Optional<String> clientSpiffeId(HttpServletRequest request) {
        Object attribute = request.getAttribute(CLIENT_CERTIFICATE_ATTRIBUTE);
        if (!(attribute instanceof X509Certificate[] chain) || chain.length == 0) {
            return Optional.empty();
        }
        return SpiffeIds.fromCertificate(chain[0]);
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s"}
                """.formatted(status.getReasonPhrase(), status.value(), detail));
    }
}

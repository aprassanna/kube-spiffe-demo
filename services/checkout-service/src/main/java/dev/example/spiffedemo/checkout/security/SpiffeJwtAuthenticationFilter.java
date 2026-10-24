package dev.example.spiffedemo.checkout.security;

import io.spiffe.exception.AuthorityNotFoundException;
import io.spiffe.exception.BundleNotFoundException;
import io.spiffe.exception.JwtSvidException;
import io.spiffe.svid.jwtsvid.JwtSvid;
import io.spiffe.workloadapi.JwtSource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every /api/** request with a SPIFFE JWT-SVID presented as a bearer token.
 *
 * <ol>
 *   <li>No or malformed token, bad signature, wrong audience or expired: 401.</li>
 *   <li>Valid token whose SPIFFE ID is not in the allow-list: 403.</li>
 *   <li>Otherwise the caller's SPIFFE ID is stored as a request attribute and the request proceeds.</li>
 * </ol>
 *
 * The signature is verified against the SPIRE trust bundle, which the Workload API keeps current.
 */
@Component
public class SpiffeJwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String CALLER_ATTRIBUTE = "spiffe.caller";

    private static final Logger log = LoggerFactory.getLogger(SpiffeJwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtSource jwtSource;
    private final String audience;
    private final Set<String> allowedCallers;

    public SpiffeJwtAuthenticationFilter(JwtSource jwtSource,
                                         @Value("${spiffe.audience}") String audience,
                                         @Value("${spiffe.allowed-callers}") Set<String> allowedCallers) {
        this.jwtSource = jwtSource;
        this.audience = audience;
        this.allowedCallers = Set.copyOf(allowedCallers);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            reject(response, HttpStatus.UNAUTHORIZED, "A bearer JWT-SVID is required");
            return;
        }

        String caller;
        try {
            JwtSvid svid = JwtSvid.parseAndValidate(
                    header.substring(BEARER_PREFIX.length()).trim(), jwtSource, Set.of(audience));
            caller = svid.getSpiffeId().toString();
        } catch (JwtSvidException | BundleNotFoundException | AuthorityNotFoundException e) {
            log.warn("Rejected request to {}: JWT-SVID failed validation: {}", request.getRequestURI(), e.getMessage());
            reject(response, HttpStatus.UNAUTHORIZED, "The JWT-SVID is invalid");
            return;
        }

        if (!allowedCallers.contains(caller)) {
            log.warn("Rejected request to {}: caller {} is not on the allow-list", request.getRequestURI(), caller);
            reject(response, HttpStatus.FORBIDDEN, "The calling workload is not authorized");
            return;
        }

        log.info("Authenticated caller {} for {} {}", caller, request.getMethod(), request.getRequestURI());
        request.setAttribute(CALLER_ATTRIBUTE, caller);
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s"}
                """.formatted(status.getReasonPhrase(), status.value(), detail));
    }
}

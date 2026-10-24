package dev.example.spiffedemo.checkout.tls;

import io.spiffe.bundle.x509bundle.X509Bundle;
import io.spiffe.exception.BundleNotFoundException;
import io.spiffe.spiffeid.TrustDomain;
import io.spiffe.svid.x509svid.X509Svid;
import io.spiffe.workloadapi.X509Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ssl.SslBundleRegistrar;
import org.springframework.boot.ssl.SslBundleRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the server's TLS material current. The SPIRE agent rotates X.509-SVIDs well before they
 * expire, so this polls the Workload API and, when the SVID changes, pushes the new material
 * into the Spring SSL bundle. Tomcat reloads its SSL context when the bundle is updated.
 */
@Component
public class SvidRotation implements SslBundleRegistrar {

    public static final String BUNDLE_NAME = "spiffe";

    private static final Logger log = LoggerFactory.getLogger(SvidRotation.class);

    private final X509Source source;
    private final TrustDomain trustDomain;
    private volatile SvidSnapshot current;
    private volatile SslBundleRegistry registry;

    public SvidRotation(X509Source source, @Value("${spiffe.trust-domain}") String trustDomain) {
        this.source = source;
        this.trustDomain = TrustDomain.parse(trustDomain);
        this.current = load();
        log.info("Loaded X.509-SVID {}", current.svid().getSpiffeId());
    }

    public SvidSnapshot current() {
        return current;
    }

    /** Called by Spring Boot when it builds its SSL bundle registry. */
    @Override
    public void registerBundles(SslBundleRegistry registry) {
        this.registry = registry;
        registry.registerBundle(BUNDLE_NAME, TlsMaterial.serverBundle(current));
    }

    @Scheduled(fixedDelayString = "${spiffe.refresh-interval-ms:30000}")
    public void refresh() {
        try {
            SvidSnapshot latest = load();
            if (latest.version().equals(current.version())) {
                return;
            }
            current = latest;
            if (registry != null) {
                registry.updateBundle(BUNDLE_NAME, TlsMaterial.serverBundle(latest));
            }
            log.info("Rotated X.509-SVID to {}", latest.svid().getSpiffeId());
        } catch (RuntimeException e) {
            log.warn("SVID refresh failed; keeping the current certificate: {}", e.getMessage());
        }
    }

    private SvidSnapshot load() {
        try {
            X509Svid svid = source.getX509Svid();
            X509Bundle bundle = source.getBundleForTrustDomain(trustDomain);
            String version = svid.getLeaf().getSerialNumber().toString(16) + ":"
                    + bundle.getX509Authorities().hashCode();
            return new SvidSnapshot(svid, bundle, version);
        } catch (BundleNotFoundException e) {
            throw new IllegalStateException("No X.509 bundle for trust domain " + trustDomain, e);
        }
    }
}

package dev.example.spiffedemo.cart.tls;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Set;
import javax.net.ssl.X509TrustManager;

/**
 * Validates the server's chain against the SPIRE bundle (delegate), then requires the server's
 * SPIFFE ID to be one of the expected IDs. This replaces hostname verification: the identity is
 * the SPIFFE ID, not the DNS name.
 */
final class ServerIdentityTrustManager implements X509TrustManager {

    private final X509TrustManager delegate;
    private final Set<String> expectedServerIds;

    ServerIdentityTrustManager(X509TrustManager delegate, Set<String> expectedServerIds) {
        this.delegate = delegate;
        this.expectedServerIds = Set.copyOf(expectedServerIds);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
        String serverId = SpiffeIds.fromCertificate(chain[0])
                .orElseThrow(() -> new CertificateException("server certificate carries no SPIFFE ID"));
        if (!expectedServerIds.contains(serverId)) {
            throw new CertificateException("unexpected server identity " + serverId);
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }
}

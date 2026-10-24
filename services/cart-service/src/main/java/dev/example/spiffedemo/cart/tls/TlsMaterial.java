package dev.example.spiffedemo.cart.tls;

import io.spiffe.bundle.x509bundle.X509Bundle;
import io.spiffe.svid.x509svid.X509Svid;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Builds the client-side SSL context: this workload's SVID, the SPIRE trust bundle and server identity checks. */
public final class TlsMaterial {

    private static final char[] PASSWORD = "spiffe".toCharArray();

    private TlsMaterial() {
    }

    public static SSLContext clientContext(SvidSnapshot snapshot, Set<String> expectedServerIds)
            throws GeneralSecurityException, IOException {
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore(snapshot.svid()), PASSWORD);

        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore(snapshot.bundle()));
        X509TrustManager pkix = Arrays.stream(trustManagers.getTrustManagers())
                .filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast)
                .findFirst()
                .orElseThrow(() -> new GeneralSecurityException("no X509TrustManager available"));

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(),
                new TrustManager[] {new ServerIdentityTrustManager(pkix, expectedServerIds)},
                null);
        return context;
    }

    private static KeyStore keyStore(X509Svid svid) throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("svid", svid.getPrivateKey(), PASSWORD, svid.getChainArray());
        return keyStore;
    }

    private static KeyStore trustStore(X509Bundle bundle) throws GeneralSecurityException, IOException {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        int index = 0;
        for (X509Certificate authority : bundle.getX509Authorities()) {
            trustStore.setCertificateEntry("authority-" + index++, authority);
        }
        return trustStore;
    }
}

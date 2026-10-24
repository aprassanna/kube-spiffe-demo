package dev.example.spiffedemo.checkout.tls;

import io.spiffe.bundle.x509bundle.X509Bundle;
import io.spiffe.svid.x509svid.X509Svid;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslStoreBundle;

/** Converts SPIRE material into keystores for the server's Spring SSL bundle. */
final class TlsMaterial {

    static final char[] PASSWORD = "spiffe".toCharArray();
    static final String KEY_ALIAS = "svid";

    private TlsMaterial() {
    }

    static SslBundle serverBundle(SvidSnapshot snapshot) {
        try {
            KeyStore keyStore = keyStore(snapshot.svid());
            KeyStore trustStore = trustStore(snapshot.bundle());
            String password = new String(PASSWORD);
            return SslBundle.of(
                    SslStoreBundle.of(keyStore, password, trustStore),
                    SslBundleKey.of(password, KEY_ALIAS));
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Could not build TLS material from the X.509-SVID", e);
        }
    }

    static KeyStore keyStore(X509Svid svid) throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry(KEY_ALIAS, svid.getPrivateKey(), PASSWORD, svid.getChainArray());
        return keyStore;
    }

    static KeyStore trustStore(X509Bundle bundle) throws GeneralSecurityException, IOException {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        int index = 0;
        for (X509Certificate authority : bundle.getX509Authorities()) {
            trustStore.setCertificateEntry("authority-" + index++, authority);
        }
        return trustStore;
    }
}

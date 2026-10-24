package dev.example.spiffedemo.cart.tls;

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class SpiffeIds {

    private static final int URI_SAN_TYPE = 6;

    private SpiffeIds() {
    }

    /** Returns the SPIFFE ID in the URI SAN of an X.509-SVID leaf certificate, if present. */
    public static Optional<String> fromCertificate(X509Certificate certificate) {
        try {
            Collection<List<?>> sans = certificate.getSubjectAlternativeNames();
            if (sans == null) {
                return Optional.empty();
            }
            return sans.stream()
                    .filter(san -> Integer.valueOf(URI_SAN_TYPE).equals(san.get(0)))
                    .map(san -> (String) san.get(1))
                    .filter(uri -> uri.startsWith("spiffe://"))
                    .findFirst();
        } catch (CertificateParsingException e) {
            return Optional.empty();
        }
    }
}

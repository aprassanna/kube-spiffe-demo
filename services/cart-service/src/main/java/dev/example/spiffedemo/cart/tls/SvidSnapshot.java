package dev.example.spiffedemo.cart.tls;

import io.spiffe.bundle.x509bundle.X509Bundle;
import io.spiffe.svid.x509svid.X509Svid;

/**
 * The workload's X.509-SVID together with the trust bundle it must be validated against.
 * A new snapshot is created only when the SVID or the bundle changes, so identity comparison
 * tells callers whether anything rotated.
 */
public record SvidSnapshot(X509Svid svid, X509Bundle bundle, String version) {
}

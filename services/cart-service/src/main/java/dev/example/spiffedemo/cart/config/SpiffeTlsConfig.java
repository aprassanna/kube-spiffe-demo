package dev.example.spiffedemo.cart.config;

import io.spiffe.exception.SocketEndpointAddressException;
import io.spiffe.exception.X509SourceException;
import io.spiffe.workloadapi.DefaultX509Source;
import io.spiffe.workloadapi.X509Source;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SpiffeTlsConfig {

    /**
     * Client for the SPIRE agent Workload API, located via the SPIFFE_ENDPOINT_SOCKET environment
     * variable. Blocks at startup until the first X.509-SVID and trust bundle arrive.
     */
    @Bean(destroyMethod = "close")
    X509Source x509Source() throws X509SourceException, SocketEndpointAddressException {
        return DefaultX509Source.newSource();
    }
}

package dev.example.spiffedemo.cart.config;

import io.spiffe.exception.JwtSourceException;
import io.spiffe.exception.SocketEndpointAddressException;
import io.spiffe.workloadapi.DefaultJwtSource;
import io.spiffe.workloadapi.JwtSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SpiffeConfig {

    /**
     * Client for the SPIRE agent Workload API, located via the SPIFFE_ENDPOINT_SOCKET
     * environment variable. Used to fetch this workload's JWT-SVIDs.
     */
    @Bean(destroyMethod = "close")
    JwtSource jwtSource() throws JwtSourceException, SocketEndpointAddressException {
        return DefaultJwtSource.newSource();
    }
}

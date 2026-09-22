package com.procuremind.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * ProcureMind identity provider.
 *
 * <p>Runs Spring Authorization Server as an OIDC provider (issuer, discovery, JWKS,
 * authorize / token / userinfo / logout endpoints and a hosted login page). It owns the
 * {@code procuremind_auth} database and holds no business data.
 *
 * <p>See {@code docs/AUTH_IMPLEMENTATION_PLAN.md} (Phase 1).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}

package com.procuremind.auth.config;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code auth.*} configuration tree (see {@code docs/AUTH_IMPLEMENTATION_PLAN.md}
 * sections 13 and 18). All secret-bearing fields default to blank and are supplied via
 * environment variables.
 */
@Getter
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    private final Jwk jwk = new Jwk();
    private final Admin admin = new Admin();
    private final Clients clients = new Clients();
    private final Token token = new Token();

    /** RSA signing-key source. */
    @Setter
    @Getter
    public static class Jwk {
        /** Filesystem path to a PKCS12 keystore. Blank -> generate an in-memory key. */
        private String keystorePath = "";
        private String keystorePassword = "";
        private String keyAlias = "auth-signing";
        private String keyPassword = "";
        /** Stable JWK key id, advertised in the JWKS document. */
        private String keyId = "auth-signing";

    }

    /** Bootstrap administrator, seeded on first start when a password is supplied. */
    @Setter
    @Getter
    public static class Admin {
        private String username = "admin";
        private String password = "";

    }

    @Getter
    public static class Clients {
        private final Client react = new Client();
        private final Client streamlit = new Client();

    }

    /** A single OAuth2 client registration seeded by {@code DataSeeder}. */
    @Setter
    @Getter
    public static class Client {
        private String clientId = "";
        /** Only set for the confidential (Streamlit) client. Blank -> public client. */
        private String clientSecret = "";
        private String redirectUri = "";
        private String postLogoutRedirectUri = "";

    }

    @Setter
    @Getter
    public static class Token {
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        private Duration refreshTokenTtl = Duration.ofHours(8);

    }
}

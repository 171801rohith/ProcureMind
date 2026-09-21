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
    private final RateLimit rateLimit = new RateLimit();

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
        /**
         * Redirect target for the hidden silent-renew iframe. Spring Authorization Server
         * refuses any redirect_uri that is not registered, so this has to be seeded
         * alongside the main one or prompt=none renewal fails with invalid_request.
         * Blank means the client does not use silent renew.
         */
        private String silentRedirectUri = "";
        private String postLogoutRedirectUri = "";

    }

    @Setter
    @Getter
    public static class Token {
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        private Duration refreshTokenTtl = Duration.ofHours(8);

    }

    /**
     * In-memory (Bucket4j + Caffeine, no Redis) rate limiting for {@code /login} and
     * {@code /oauth2/token} (see {@code ARCHITECTURE_REVIEW.md} finding #5). Two independent
     * dimensions are limited per protected endpoint: the caller's IP address and the identity
     * it is acting as (the form-login {@code username}, or the OAuth2 client id for the token
     * endpoint). Either limit being exceeded rejects the request with HTTP 429.
     */
    @Setter
    @Getter
    public static class RateLimit {
        private boolean enabled = true;
        // Per-IP is more generous than per-identity: one IP can legitimately front several
        // users (NAT/shared office network), so it exists as a coarse flood backstop rather
        // than the primary brute-force defense.
        private final Limit ip = new Limit(20);
        // Per-identity is the primary brute-force defense: 5 attempts/minute is enough for a
        // genuine typo or two but throttles a credential-stuffing run against one account.
        private final Limit identity = new Limit(5);
    }

    @Setter
    @Getter
    public static class Limit {
        private int capacity;
        private Duration period = Duration.ofMinutes(1);

        public Limit() {
            this.capacity = 10;
        }

        public Limit(int capacity) {
            this.capacity = capacity;
        }
    }
}

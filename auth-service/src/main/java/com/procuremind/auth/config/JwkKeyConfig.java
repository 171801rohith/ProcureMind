package com.procuremind.auth.config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Supplies the RSA key used to sign tokens, overriding Spring Boot's autoconfigured
 * ephemeral key (see {@code docs/AUTH_IMPLEMENTATION_PLAN.md} section 10).
 *
 * <ul>
 *   <li>When {@code auth.jwk.keystore-path} points at a readable PKCS12 file, the key is
 *       loaded from it — this is the persistent, production/compose path.</li>
 *   <li>Otherwise, if the {@code dev} Spring profile is active, an in-memory RSA-2048 key is
 *       generated with the configured, stable {@code kid}. This keeps local runs and tests
 *       self-contained; tokens do not survive a restart. A warning is logged.</li>
 *   <li>Otherwise (no keystore, and {@code dev} not active) startup fails fast with an
 *       {@link IllegalStateException}, since silently issuing tokens from a key that won't
 *       survive a restart would invalidate every session without warning.</li>
 * </ul>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class JwkKeyConfig {

    private static final String DEV_PROFILE = "dev";

    @Bean
    public JWKSource<SecurityContext> jwkSource(AuthProperties properties, Environment environment) {
        RSAKey rsaKey = buildRsaKey(properties.getJwk(), environment);
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    private RSAKey buildRsaKey(AuthProperties.Jwk jwk, Environment environment) {
        String keystorePath = jwk.getKeystorePath();
        if (StringUtils.hasText(keystorePath) && Files.isReadable(Path.of(keystorePath))) {
            return loadFromKeystore(jwk);
        }

        String reason = StringUtils.hasText(keystorePath)
                ? "auth.jwk.keystore-path='" + keystorePath + "' is not readable"
                : "no auth.jwk.keystore-path is configured";

        if (!environment.matchesProfiles(DEV_PROFILE)) {
            throw new IllegalStateException(reason + ", and the '" + DEV_PROFILE + "' Spring profile is not "
                    + "active. Refusing to start with a non-persistent signing key: every restart would silently "
                    + "invalidate every issued token/session. Generate a keystore with "
                    + "scripts/gen-auth-keystore.sh and set AUTH_JWK_KEYSTORE_PATH, or activate the '"
                    + DEV_PROFILE + "' profile (SPRING_PROFILES_ACTIVE=dev) for local-only runs.");
        }

        log.warn("{}; generating a NON-PERSISTENT in-memory RSA signing key (kid='{}') because the '{}' profile "
                + "is active. Tokens will not survive a restart.", reason, jwk.getKeyId(), DEV_PROFILE);
        return generateInMemory(jwk.getKeyId());
    }

    private RSAKey loadFromKeystore(AuthProperties.Jwk jwk) {
        try (InputStream in = Files.newInputStream(Path.of(jwk.getKeystorePath()))) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            char[] storePassword = jwk.getKeystorePassword().toCharArray();
            keyStore.load(in, storePassword);

            char[] keyPassword = StringUtils.hasText(jwk.getKeyPassword())
                    ? jwk.getKeyPassword().toCharArray()
                    : storePassword;
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(jwk.getKeyAlias(), keyPassword);
            if (privateKey == null) {
                throw new IllegalStateException("No key found under alias '" + jwk.getKeyAlias() + "'");
            }
            Certificate certificate = keyStore.getCertificate(jwk.getKeyAlias());
            RSAPublicKey publicKey = (RSAPublicKey) certificate.getPublicKey();

            log.info("Loaded RSA signing key from keystore '{}' (alias='{}', kid='{}')",
                    jwk.getKeystorePath(), jwk.getKeyAlias(), jwk.getKeyId());
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .keyID(jwk.getKeyId())
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load RSA signing key from keystore '"
                    + jwk.getKeystorePath() + "'", ex);
        }
    }

    private RSAKey generateInMemory(String keyId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey(keyPair.getPrivate())
                    .keyUse(KeyUse.SIGNATURE)
                    .keyID(keyId)
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate in-memory RSA signing key", ex);
        }
    }
}

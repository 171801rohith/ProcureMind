package com.procuremind.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit test for the JWK source (section 10). With no keystore configured and the {@code dev}
 * profile active it must still yield exactly one usable RSA key carrying the configured,
 * stable {@code kid}; outside {@code dev} it must fail fast instead of silently issuing a
 * non-persistent key.
 */
class JwkSourceTest {

    @Test
    void generatesSingleStableRsaKeyWhenNoKeystoreConfiguredAndDevProfileActive() throws Exception {
        AuthProperties properties = new AuthProperties();
        properties.getJwk().setKeystorePath("");
        properties.getJwk().setKeyId("unit-test-kid");
        MockEnvironment devEnv = new MockEnvironment();
        devEnv.addActiveProfile("dev");

        JWKSource<SecurityContext> source = new JwkKeyConfig().jwkSource(properties, devEnv);

        JWKSet set = ((ImmutableJWKSet<SecurityContext>) source).getJWKSet();
        assertThat(set.getKeys()).hasSize(1);

        JWK jwk = set.getKeys().get(0);
        assertThat(jwk.getKeyType()).isEqualTo(KeyType.RSA);
        assertThat(jwk.getKeyID()).isEqualTo("unit-test-kid");
        assertThat(jwk.isPrivate()).isTrue();

        RSAKey rsaKey = jwk.toRSAKey();
        assertThat(rsaKey.toRSAPrivateKey()).isNotNull();
        assertThat(rsaKey.toRSAPublicKey().getModulus().bitLength()).isGreaterThanOrEqualTo(2040);
    }

    @Test
    void reusesConfiguredKeyIdOnEachBuild() {
        AuthProperties properties = new AuthProperties();
        properties.getJwk().setKeyId("shared-kid");
        MockEnvironment devEnv = new MockEnvironment();
        devEnv.addActiveProfile("dev");

        JWKSet first = ((ImmutableJWKSet<SecurityContext>) new JwkKeyConfig().jwkSource(properties, devEnv)).getJWKSet();
        JWKSet second = ((ImmutableJWKSet<SecurityContext>) new JwkKeyConfig().jwkSource(properties, devEnv)).getJWKSet();

        assertThat(first.getKeys().get(0).getKeyID()).isEqualTo("shared-kid");
        assertThat(second.getKeys().get(0).getKeyID()).isEqualTo("shared-kid");
    }

    @Test
    void throwsWhenKeystorePathUnsetAndDevProfileNotActive() {
        AuthProperties properties = new AuthProperties();
        properties.getJwk().setKeystorePath("");
        MockEnvironment nonDevEnv = new MockEnvironment();

        assertThatThrownBy(() -> new JwkKeyConfig().jwkSource(properties, nonDevEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev");
    }

    @Test
    void throwsWhenKeystorePathUnreadableAndDevProfileNotActive() {
        AuthProperties properties = new AuthProperties();
        properties.getJwk().setKeystorePath("/nonexistent/path/does-not-exist.p12");
        MockEnvironment nonDevEnv = new MockEnvironment();

        assertThatThrownBy(() -> new JwkKeyConfig().jwkSource(properties, nonDevEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev");
    }
}

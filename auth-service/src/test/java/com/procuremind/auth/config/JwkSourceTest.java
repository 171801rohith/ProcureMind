package com.procuremind.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.junit.jupiter.api.Test;

/**
 * Unit test for the JWK source (section 10). With no keystore configured it must still
 * yield exactly one usable RSA key carrying the configured, stable {@code kid}.
 */
class JwkSourceTest {

    @Test
    void generatesSingleStableRsaKeyWhenNoKeystoreConfigured() throws Exception {
        AuthProperties properties = new AuthProperties();
        properties.getJwk().setKeystorePath("");
        properties.getJwk().setKeyId("unit-test-kid");

        JWKSource<SecurityContext> source = new JwkKeyConfig().jwkSource(properties);

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

        JWKSet first = ((ImmutableJWKSet<SecurityContext>) new JwkKeyConfig().jwkSource(properties)).getJWKSet();
        JWKSet second = ((ImmutableJWKSet<SecurityContext>) new JwkKeyConfig().jwkSource(properties)).getJWKSet();

        assertThat(first.getKeys().get(0).getKeyID()).isEqualTo("shared-kid");
        assertThat(second.getKeys().get(0).getKeyID()).isEqualTo("shared-kid");
    }
}

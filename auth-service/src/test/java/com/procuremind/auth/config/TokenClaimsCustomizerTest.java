package com.procuremind.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * Unit test for the {@code roles} claim customizer (section 13). No Spring context: the
 * {@code @Bean} method is invoked directly.
 */
class TokenClaimsCustomizerTest {

    private final OAuth2TokenCustomizer<JwtEncodingContext> customizer =
            new AuthorizationServerConfig().rolesTokenCustomizer();

    @Test
    void addsFlattenedRoleClaimToAccessToken() {
        JwtEncodingContext context = contextFor(OAuth2TokenType.ACCESS_TOKEN,
                "ROLE_ANALYST", "ROLE_VIEWER", "SCOPE_openid");

        customizer.customize(context);

        assertThat(context.getClaims().build().getClaims().get("roles"))
                .asInstanceOf(InstanceOfAssertFactories.iterable(String.class))
                .containsExactlyInAnyOrder("ANALYST", "VIEWER");
    }

    @Test
    void ignoresNonRoleAuthorities() {
        JwtEncodingContext context = contextFor(OAuth2TokenType.ACCESS_TOKEN, "SCOPE_openid", "profile");

        customizer.customize(context);

        assertThat(context.getClaims().build().getClaims().get("roles"))
                .asInstanceOf(InstanceOfAssertFactories.iterable(String.class))
                .isEmpty();
    }

    @Test
    void leavesUnrelatedTokenTypesUntouched() {
        JwtEncodingContext context = contextFor(new OAuth2TokenType("some_other_token"), "ROLE_ADMIN");

        customizer.customize(context);

        assertThat(context.getClaims().build().getClaims()).doesNotContainKey("roles");
    }

    private static JwtEncodingContext contextFor(OAuth2TokenType tokenType, String... authorities) {
        TestingAuthenticationToken principal = new TestingAuthenticationToken("alice", "n/a", authorities);
        return JwtEncodingContext
                .with(JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder().subject("alice"))
                .principal(principal)
                .tokenType(tokenType)
                .build();
    }
}

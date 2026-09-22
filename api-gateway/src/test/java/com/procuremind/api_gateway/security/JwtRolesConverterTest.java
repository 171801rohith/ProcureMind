package com.procuremind.api_gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit test for the {@code roles} claim -> {@code ROLE_*} authority mapping. No Spring
 * context; the converter is exercised directly (see Phase 3/4, section 23).
 *
 * <p>Spring Security 7 also attaches a {@code FACTOR_BEARER} authority to every JWT
 * authentication, so assertions target only the {@code ROLE_*} authorities this converter
 * is responsible for.
 */
class JwtRolesConverterTest {

    private final JwtRolesConverter converter = new JwtRolesConverter();

    @Test
    void mapsEachRoleToAPrefixedAuthority() {
        AbstractAuthenticationToken token = converter.convert(jwtWithRoles(List.of("ANALYST", "VIEWER")));

        assertThat(roleAuthorities(token)).containsExactlyInAnyOrder("ROLE_ANALYST", "ROLE_VIEWER");
    }

    @Test
    void yieldsNoRoleAuthoritiesWhenTheRolesClaimIsMissing() {
        AbstractAuthenticationToken token = converter.convert(jwt(Map.of("sub", "alice")));

        assertThat(roleAuthorities(token)).isEmpty();
    }

    @Test
    void ignoresARolesClaimThatIsNotACollection() {
        AbstractAuthenticationToken token = converter.convert(jwt(Map.of("sub", "alice", "roles", "ADMIN")));

        assertThat(roleAuthorities(token)).isEmpty();
    }

    private static List<String> roleAuthorities(AbstractAuthenticationToken token) {
        return token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .toList();
    }

    private static Jwt jwtWithRoles(List<String> roles) {
        return jwt(Map.of("sub", "alice", "roles", roles));
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), claims);
    }
}

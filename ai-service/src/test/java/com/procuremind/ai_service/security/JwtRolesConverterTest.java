package com.procuremind.ai_service.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit test for the {@code roles} claim -> {@code ROLE_*} authority mapping (section 23).
 * Spring Security 6 on this module; assertions target only the {@code ROLE_*} authorities
 * this converter owns.
 */
class JwtRolesConverterTest {

    private final JwtRolesConverter converter = new JwtRolesConverter();

    @Test
    void mapsEachRoleToAPrefixedAuthority() {
        AbstractAuthenticationToken token = converter.convert(jwt(Map.of("sub", "alice", "roles", List.of("VIEWER", "ANALYST"))));

        assertThat(roleAuthorities(token)).containsExactlyInAnyOrder("ROLE_VIEWER", "ROLE_ANALYST");
    }

    @Test
    void yieldsNoRoleAuthoritiesWhenTheRolesClaimIsMissing() {
        assertThat(roleAuthorities(converter.convert(jwt(Map.of("sub", "alice"))))).isEmpty();
    }

    @Test
    void ignoresARolesClaimThatIsNotACollection() {
        assertThat(roleAuthorities(converter.convert(jwt(Map.of("sub", "alice", "roles", "ADMIN"))))).isEmpty();
    }

    private static List<String> roleAuthorities(AbstractAuthenticationToken token) {
        return token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .toList();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), claims);
    }
}

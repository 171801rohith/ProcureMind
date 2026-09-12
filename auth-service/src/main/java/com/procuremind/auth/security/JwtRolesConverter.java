package com.procuremind.auth.security;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Maps the flat {@code roles} claim minted by auth-service (e.g. {@code ["ANALYST"]}) to
 * Spring Security {@code ROLE_*} authorities. Used by the ADMIN-only /api/users chain so
 * auth-service authorizes its own API from the same claim every resource server reads.
 */
public final class JwtRolesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();

    public JwtRolesConverter() {
        this.delegate.setJwtGrantedAuthoritiesConverter(JwtRolesConverter::extractRoleAuthorities);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return this.delegate.convert(jwt);
    }

    private static Collection<GrantedAuthority> extractRoleAuthorities(Jwt jwt) {
        Object claim = jwt.getClaim("roles");
        if (!(claim instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(Objects::nonNull)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}

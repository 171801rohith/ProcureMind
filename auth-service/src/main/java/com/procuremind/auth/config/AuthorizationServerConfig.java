package com.procuremind.auth.config;

import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;

import com.procuremind.auth.security.ratelimit.RateLimitFilter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Spring Authorization Server wiring (see {@code docs/AUTH_IMPLEMENTATION_PLAN.md} section 10).
 *
 * <p>The authorization-server filter chain mirrors Spring Boot's
 * {@code OAuth2AuthorizationServerWebSecurityConfiguration}; it is declared here (rather
 * than relying on that autoconfiguration) so that the sibling {@code DefaultSecurityConfig}
 * chain can leave the actuator health and login endpoints unauthenticated.
 *
 * <p>OAuth2 state ({@code oauth2_registered_client}, {@code oauth2_authorization},
 * {@code oauth2_authorization_consent}) is persisted in PostgreSQL so tokens and client
 * registrations survive a restart. A {@code roles} claim is added to access and id tokens
 * for downstream resource servers.
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationServerConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http, RateLimitFilter rateLimitFilter)
            throws Exception {
        http.oauth2AuthorizationServer((authorizationServer) -> {
            http.securityMatcher(authorizationServer.getEndpointsMatcher());
            authorizationServer.oidc(Customizer.withDefaults());
        });
        http.authorizeHttpRequests((authorize) -> authorize.anyRequest().authenticated());
        http.cors(Customizer.withDefaults());
        http.oauth2ResourceServer((resourceServer) -> resourceServer.jwt(Customizer.withDefaults()));
        http.exceptionHandling((exceptions) -> exceptions.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"), htmlRequestMatcher()));
        // Same shared filter as DefaultSecurityConfig (see RateLimitFilterConfig); this chain
        // is the one that actually sees POST /oauth2/token, since its securityMatcher scopes
        // it to the authorization-server endpoints.
        http.addFilterBefore(rateLimitFilter, DisableEncodeUrlFilter.class);
        return http.build();
    }

    private static RequestMatcher htmlRequestMatcher() {
        MediaTypeRequestMatcher matcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        matcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        return matcher;
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate,
            RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbcTemplate,
            RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    /**
     * Adds a {@code roles} claim (e.g. {@code ["ANALYST"]}) to access and id tokens, taken
     * from the authenticated principal's {@code ROLE_*} authorities with the prefix
     * stripped. Resource servers re-apply the {@code ROLE_} prefix when mapping authorities.
     */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> rolesTokenCustomizer() {
        return context -> {
            OAuth2TokenType tokenType = context.getTokenType();
            if (tokenType == null) {
                return;
            }
            boolean accessToken = OAuth2TokenType.ACCESS_TOKEN.equals(tokenType);
            boolean idToken = OidcParameterNames.ID_TOKEN.equals(tokenType.getValue());
            if (!accessToken && !idToken) {
                return;
            }
            if (context.getPrincipal() == null || context.getPrincipal().getAuthorities() == null) {
                return;
            }
            Set<String> roles = new TreeSet<>();
            for (GrantedAuthority authority : context.getPrincipal().getAuthorities()) {
                String value = authority.getAuthority();
                if (value != null && value.startsWith("ROLE_")) {
                    roles.add(value.substring("ROLE_".length()));
                }
            }
            // Deliberately an ArrayList rather than the TreeSet above.
            //
            // These claims are persisted with the authorization in oidc_id_token_metadata and
            // read back by JdbcOAuth2AuthorizationService, whose Jackson mapper only trusts
            // the types on Spring Security's allow-list. TreeSet is not on it, so RP-initiated
            // logout failed with "Could not resolve type id 'java.util.TreeSet'", returned 400,
            // and left the authorization-server session alive. The TreeSet still does the
            // de-duplication and ordering; only the stored type changes, so the JSON claim is
            // unchanged.
            context.getClaims().claim("roles", new ArrayList<>(roles));
        };
    }
}

package com.procuremind.ai_service.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Makes ai-service an independent OAuth2 resource server (see
 * docs/AUTH_IMPLEMENTATION_PLAN.md sections 9 and 12). It validates the JWT itself rather
 * than trusting the gateway or any {@code X-User-*} header — a request that reaches port
 * 8082 directly is authenticated and authorized exactly as one arriving via the gateway.
 * Spring Boot 3.5.x / Spring Security 6; the token is the same RS256 JWT the Boot 4
 * services accept, validated against the same JWKS.
 *
 * <p><b>Enforcement is the default.</b> {@code app.security.enforce}
 * ({@code ${AUTH_ENABLED:true}}) selects one of two mutually exclusive chains; the
 * permissive one exists only as a documented emergency kill switch
 * ({@code AUTH_ENABLED=false}) and still validates any token that is presented.
 *
 * <p>Method security lives in {@code MethodSecurityConfig}, behind the same property, so
 * the {@code @PreAuthorize} rules on the controllers are active exactly when these chains
 * enforce and are inert when the kill switch is thrown.
 *
 * <p>The Kafka listeners that drive the parse -&gt; index -&gt; analyze pipeline run
 * outside any HTTP request and have no {@code SecurityContext}; these chains do not apply
 * to them.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    static final String[] PUBLIC_PATHS = {
            "/actuator/health", "/actuator/health/**",
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    @Bean
    @ConditionalOnProperty(name = "app.security.enforce", havingValue = "true", matchIfMissing = true)
    SecurityFilterChain enforcingSecurityFilterChain(HttpSecurity http, JwtRolesConverter rolesConverter)
            throws Exception {
        baseline(http, rolesConverter);
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .anyRequest().authenticated());
        return http.build();
    }

    /** Emergency kill switch: {@code AUTH_ENABLED=false}. Tokens are still validated. */
    @Bean
    @ConditionalOnProperty(name = "app.security.enforce", havingValue = "false")
    SecurityFilterChain permissiveSecurityFilterChain(HttpSecurity http, JwtRolesConverter rolesConverter)
            throws Exception {
        baseline(http, rolesConverter);
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    private static void baseline(HttpSecurity http, JwtRolesConverter rolesConverter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter)));
    }

    @Bean
    JwtRolesConverter jwtRolesConverter() {
        return new JwtRolesConverter();
    }
}

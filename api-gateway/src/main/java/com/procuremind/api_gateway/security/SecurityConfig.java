package com.procuremind.api_gateway.security;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Makes the API gateway an OAuth2 resource server and applies the coarse-grained role
 * rules (see docs/AUTH_IMPLEMENTATION_PLAN.md sections 7 and 12).
 *
 * <p><b>Enforcement is the default.</b> {@code app.security.enforce}
 * ({@code ${AUTH_ENABLED:true}}) selects one of two mutually exclusive chains:
 * <ul>
 *   <li><b>enforcing</b> (default) — the public paths stay open, {@code /actuator/**}
 *       detail is ADMIN-only, and every {@code /api/**} route needs an appropriate role.</li>
 *   <li><b>permissive</b> — only when {@code AUTH_ENABLED=false} is set explicitly. This is
 *       a documented <em>emergency kill switch</em> for incident response, not a normal
 *       operating mode: a presented token is still validated, but anonymous requests pass.</li>
 * </ul>
 * An unrecognised value registers neither chain, so Spring Boot's own locked-down default
 * applies — the failure mode is closed, never open.
 *
 * <p>No {@code X-User-*} headers are added or trusted; the gateway validates the JWT and
 * forwards the original {@code Authorization} header downstream, where each service
 * validates it again independently.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /** Open to anonymous callers: gateway landing page, route index and health probes. */
    static final String[] PUBLIC_PATHS = {
            "/", "/api", "/actuator/health", "/actuator/health/**", "/health/**"
    };

    /** Any authenticated role may read. */
    static final String[] READ_ROLES = { "VIEWER", "ANALYST", "ADMIN" };

    /** Roles allowed to ingest contracts and use the LLM assistant. */
    static final String[] WRITE_ROLES = { "ANALYST", "ADMIN" };

    @Bean
    @ConditionalOnProperty(name = "app.security.enforce", havingValue = "true", matchIfMissing = true)
    SecurityFilterChain enforcingSecurityFilterChain(HttpSecurity http, JwtRolesConverter rolesConverter)
            throws Exception {
        baseline(http, rolesConverter);
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(PUBLIC_PATHS).permitAll()
                // Costly / state-changing operations require ANALYST or ADMIN.
                .requestMatchers(HttpMethod.POST, "/api/contracts/upload").hasAnyRole(WRITE_ROLES)
                .requestMatchers(HttpMethod.POST, "/api/analysis/chat").hasAnyRole(WRITE_ROLES)
                // Read models are open to every authenticated role.
                .requestMatchers(HttpMethod.GET, "/api/contracts", "/api/contracts/**").hasAnyRole(READ_ROLES)
                .requestMatchers(HttpMethod.GET, "/api/analysis", "/api/analysis/**").hasAnyRole(READ_ROLES)
                // User administration and actuator detail are administrative.
                .requestMatchers("/api/users", "/api/users/**").hasRole("ADMIN")
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
                .cors(Customizer.withDefaults())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter)));
    }

    @Bean
    JwtRolesConverter jwtRolesConverter() {
        return new JwtRolesConverter();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

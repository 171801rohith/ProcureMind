package com.procuremind.auth.config;

import com.procuremind.auth.security.JwtRolesConverter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Bearer-token security for auth-service's own REST API (Phase 8).
 *
 * <p>Sits between the authorization-server chain (highest precedence, OAuth2 endpoints) and
 * the form-login chain (order 2). It scopes itself to {@code /api/**} and treats
 * auth-service as a resource server for the tokens it issues, so {@code /api/users/**}
 * requires a JWT carrying the {@code ADMIN} role — the same claim every other service reads.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class ApiSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtRolesConverter rolesConverter)
            throws Exception {
        http
                .securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/users/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter)));
        return http.build();
    }

    @Bean
    JwtRolesConverter jwtRolesConverter() {
        return new JwtRolesConverter();
    }
}

package com.procuremind.auth.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Non–authorization-server web security: the branded hosted login page, the public health/info
 * probes, the password encoder shared by form login and OAuth2 client-secret checks, and a
 * narrow CORS policy for the token / JWKS / userinfo endpoints the browser calls
 * (see {@code docs/AUTH_IMPLEMENTATION_PLAN.md} sections 10 and 15).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class DefaultSecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests((authorize) -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/login", "/error", "/css/**", "/default-ui.css", "/webjars/**", "/assets/**")
                        .permitAll()
                        .anyRequest().authenticated())
                // Only the rendered page changes: credentials still post to Spring Security's
                // own /login processing endpoint and are still checked by JpaUserDetailsService.
                .formLogin(form -> form
                        .loginPage("/login")
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Strength 12 (see docs/AUTH_IMPLEMENTATION_PLAN.md section 12). Used both for user
        // passwords (form login) and for the confidential client's secret.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @org.springframework.beans.factory.annotation.Value("${auth.clients.react.redirect-uri:http://localhost:5173/}")
            String reactRedirectUri) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(stripTrailingSlash(reactRedirectUri)));
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/oauth2/token", config);
        source.registerCorsConfiguration("/oauth2/revoke", config);
        source.registerCorsConfiguration("/oauth2/jwks", config);
        source.registerCorsConfiguration("/userinfo", config);
        source.registerCorsConfiguration("/connect/**", config);
        source.registerCorsConfiguration("/.well-known/**", config);
        return source;
    }

    private static String stripTrailingSlash(String value) {
        return (value != null && value.endsWith("/")) ? value.substring(0, value.length() - 1) : value;
    }
}

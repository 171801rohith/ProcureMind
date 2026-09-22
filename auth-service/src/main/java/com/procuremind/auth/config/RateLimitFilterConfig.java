package com.procuremind.auth.config;

import com.procuremind.auth.security.ratelimit.RateLimitFilter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the single {@link RateLimitFilter} instance shared by both
 * {@link DefaultSecurityConfig} (owns {@code /login}) and {@link AuthorizationServerConfig}
 * (owns {@code /oauth2/token}), so the two independent Caffeine-backed limiters inside it are
 * not duplicated per filter chain. See {@code ARCHITECTURE_REVIEW.md} finding #5.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitFilterConfig {

    @Bean
    RateLimitFilter rateLimitFilter(AuthProperties authProperties) {
        return new RateLimitFilter(authProperties);
    }
}

package com.procuremind.api_gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the resource-server {@link JwtDecoder} from the JWKS endpoint and validates the
 * {@code iss} claim against the static, browser-facing issuer. Keeping the JWKS URL and the
 * issuer separate lets containers fetch keys over the internal network while still checking
 * the public issuer value (see docs/AUTH_IMPLEMENTATION_PLAN.md section 13).
 */
@Configuration(proxyBeanMethods = false)
public class JwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${app.security.issuer-uri}") String issuerUri) {

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), new JwtIssuerValidator(issuerUri));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}

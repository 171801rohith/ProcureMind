package com.procuremind.ai_service.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Enables {@code @PreAuthorize} processing, under the same switch as the filter chains.
 *
 * <p><b>Why this is a separate class.</b> {@link EnableMethodSecurity} registers its
 * advisors at configuration-parsing time, so it cannot be turned off later by a runtime
 * flag. Leaving it on {@code SecurityConfig} meant the controllers' {@code @PreAuthorize}
 * annotations kept rejecting anonymous callers even with {@code AUTH_ENABLED=false},
 * which made the documented kill switch a half-measure: the filter chain went permissive
 * but the method interceptor did not. Hosting the annotation on its own
 * {@code @ConditionalOnProperty} configuration is the supported way to make the whole
 * authorization stack follow one flag.
 *
 * <p><b>The trade-off.</b> When the switch is off the {@code @PreAuthorize} annotations
 * become inert rather than failing loudly. That is precisely the kill-switch contract, and
 * it is why {@code AUTH_ENABLED=false} is an incident-response lever that must never be a
 * normal operating mode. The condition matches when the property is missing, so the secured
 * path is the default and an unrecognised value registers neither this nor a filter chain,
 * leaving the application locked down rather than open.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.security.enforce", havingValue = "true", matchIfMissing = true)
@EnableMethodSecurity
public class MethodSecurityConfig {
}

package com.procuremind.auth.security.ratelimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.procuremind.auth.config.AuthProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limits the two credential-checking endpoints, {@code POST /login} (form login) and
 * {@code POST /oauth2/token} (Spring Authorization Server), per finding #5 of
 * {@code ARCHITECTURE_REVIEW.md}.
 *
 * <p>Two independent Bucket4j/Caffeine limiters back this filter (see
 * {@link RateLimiterService}): one keyed by caller IP, one keyed by the identity the request
 * claims to act as (the {@code username} form field for {@code /login}; the OAuth2 client id,
 * read from the HTTP Basic credentials or the {@code client_id} parameter, for
 * {@code /oauth2/token}). Either limit being exceeded rejects the request with 429, before it
 * reaches the real authentication filters, so a lockout never depends on — or leaks — whether
 * the identity is valid.
 *
 * <p>The IP is read from {@link HttpServletRequest#getRemoteAddr()} rather than
 * {@code X-Forwarded-For}: neither {@code /login} nor {@code /oauth2/token} is proxied through
 * {@code api-gateway} today (the gateway only forwards {@code /api/users/**} to auth-service;
 * browsers hit these two endpoints on auth-service directly), so there is no trusted reverse
 * proxy in front of this filter and honoring a client-supplied header here would let an
 * attacker spoof a fresh IP on every request and bypass the limiter entirely. If a reverse
 * proxy/load balancer is ever placed in front of auth-service itself, this needs revisiting
 * (e.g. Spring's {@code ForwardedHeaderFilter} with an explicit trusted-proxy count).
 *
 * <p>One shared instance is wired into both the {@code DefaultSecurityConfig} (owns
 * {@code /login}) and {@code AuthorizationServerConfig} (owns {@code /oauth2/token}) filter
 * chains, since each chain only sees the requests matched by its own
 * {@code securityMatcher}.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final RequestMatcher LOGIN_MATCHER = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/login");
    private static final RequestMatcher TOKEN_MATCHER =
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/oauth2/token");

    private final AuthProperties.RateLimit properties;
    private final RateLimiterService ipLimiter;
    private final RateLimiterService identityLimiter;

    public RateLimitFilter(AuthProperties authProperties) {
        this.properties = authProperties.getRateLimit();
        this.ipLimiter = new RateLimiterService(properties.getIp().getCapacity(), properties.getIp().getPeriod());
        this.identityLimiter = new RateLimiterService(
                properties.getIdentity().getCapacity(), properties.getIdentity().getPeriod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.isEnabled() || !(LOGIN_MATCHER.matches(request) || TOKEN_MATCHER.matches(request))) {
            chain.doFilter(request, response);
            return;
        }

        String ip = request.getRemoteAddr();
        if (!ipLimiter.tryConsume("ip:" + ip)) {
            log.warn("Rate limit exceeded for IP on {}", request.getRequestURI());
            reject(response);
            return;
        }

        String identity = extractIdentity(request);
        if (identity != null && !identity.isBlank() && !identityLimiter.tryConsume("user:" + identity)) {
            log.warn("Rate limit exceeded for identity on {}", request.getRequestURI());
            reject(response);
            return;
        }

        chain.doFilter(request, response);
    }

    private String extractIdentity(HttpServletRequest request) {
        if (LOGIN_MATCHER.matches(request)) {
            return request.getParameter("username");
        }
        if (TOKEN_MATCHER.matches(request)) {
            return extractClientId(request);
        }
        return null;
    }

    /**
     * Confidential clients (e.g. {@code procuremind-streamlit}) authenticate with HTTP Basic
     * per {@code ClientAuthenticationMethod.CLIENT_SECRET_BASIC}; the public SPA client
     * ({@code procuremind-react}, PKCE, {@code ClientAuthenticationMethod.NONE}) has no secret
     * and instead sends {@code client_id} as a plain request parameter. Both are read here so
     * every client type gets an independent per-identity limit.
     */
    private String extractClientId(HttpServletRequest request) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                byte[] decoded = Base64.getDecoder().decode(authHeader.substring(6).trim());
                String credentials = new String(decoded, StandardCharsets.UTF_8);
                int separator = credentials.indexOf(':');
                return separator >= 0 ? credentials.substring(0, separator) : credentials;
            } catch (IllegalArgumentException ex) {
                // Malformed Basic header; fall through and try the client_id parameter instead.
                log.debug("Ignoring malformed Basic auth header on /oauth2/token");
            }
        }
        return request.getParameter("client_id");
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Deliberately generic, same spirit as the login page's error message: never reveal
        // whether the rejection was IP- or identity-based, or whether the identity even exists.
        response.getWriter().write("{\"error\":\"too_many_requests\","
                + "\"message\":\"Too many attempts. Please try again later.\"}");
    }
}

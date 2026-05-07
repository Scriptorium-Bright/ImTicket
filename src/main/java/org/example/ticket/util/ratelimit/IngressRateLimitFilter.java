package org.example.ticket.util.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class IngressRateLimitFilter extends OncePerRequestFilter {

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    @Qualifier("handlerExceptionResolver")
    private final HandlerExceptionResolver handlerExceptionResolver;

    private final Map<RouteKey, RateLimitPolicy> policies = Map.of(
            new RouteKey("POST", "/api/sms/certificate"), RateLimitPolicies.SMS_CERTIFICATE_IP,
            new RouteKey("POST", "/api/sms/verify"), RateLimitPolicies.SMS_VERIFY_IP,
            new RouteKey("GET", "/api/user/nonce"), RateLimitPolicies.NONCE_IP,
            new RouteKey("POST", "/api/user/signature/verify"), RateLimitPolicies.SIGNATURE_VERIFY_IP,
            new RouteKey("POST", "/api/entry/verify"), RateLimitPolicies.ENTRY_VERIFY_IP
    );

    @Override
    protected void doFilterInternal(
            @NotNull HttpServletRequest request,
            @NotNull HttpServletResponse response,
            @NotNull FilterChain filterChain
    ) throws ServletException, IOException {
        RateLimitPolicy policy = resolvePolicy(request);
        if (policy == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = clientIpResolver.resolve(request);

        try {
            rateLimiter.checkOrThrow(policy, clientIp);
            filterChain.doFilter(request, response);
        } catch (RateLimitException exception) {
            log.debug("Ingress rate limit rejected. policy={}, ip={}", policy.policyName(), clientIp);
            handlerExceptionResolver.resolveException(request, response, null, exception);
        }
    }

    private RateLimitPolicy resolvePolicy(HttpServletRequest request) {
        RouteKey routeKey = new RouteKey(request.getMethod(), request.getServletPath());
        return policies.get(routeKey);
    }

    private record RouteKey(String method, String path) {
    }
}

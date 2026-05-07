package org.example.ticket.util.ratelimit;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class IngressRateLimitFilterTest {

    @Mock
    private RedisFixedWindowRateLimiter rateLimiter;

    private ClientIpResolver clientIpResolver;
    private HandlerExceptionResolver handlerExceptionResolver;
    private IngressRateLimitFilter ingressRateLimitFilter;

    @BeforeEach
    void setUp() {
        clientIpResolver = new ClientIpResolver();
        handlerExceptionResolver = (request, response, handler, ex) -> {
            if (ex instanceof RateLimitException rateLimitException) {
                RateLimitDecision decision = rateLimitException.getDecision();
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
                response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
                response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
                response.setHeader("X-RateLimit-Reset", String.valueOf(decision.resetEpochSeconds()));
            }
            return null;
        };
        ingressRateLimitFilter = new IngressRateLimitFilter(rateLimiter, clientIpResolver, handlerExceptionResolver);
    }

    @Test
    void limitsConfiguredIngressRouteByClientIp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/sms/certificate");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = (req, res) -> response.setStatus(204);

        request.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.1");
        ingressRateLimitFilter.doFilter(request, response, filterChain);

        verify(rateLimiter).checkOrThrow(eq(RateLimitPolicies.SMS_CERTIFICATE_IP), eq("198.51.100.7"));
        assertThat(response.getStatus()).isEqualTo(204);
    }

    @Test
    void skipsNonTargetRouteWithoutCallingLimiter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/venue/enter");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = (req, res) -> response.setStatus(202);

        ingressRateLimitFilter.doFilter(request, response, filterChain);

        verifyNoInteractions(rateLimiter);
        assertThat(response.getStatus()).isEqualTo(202);
    }

    @Test
    void returns429HeadersWhenIngressPolicyRejects() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user/nonce");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = (req, res) -> response.setStatus(204);
        RateLimitDecision decision = RateLimitDecision.rejected(
                RateLimitPolicies.NONCE_IP,
                0,
                12,
                1710000000L,
                "limit_exceeded"
        );

        request.setRemoteAddr("203.0.113.9");
        doThrow(new RateLimitException(decision))
                .when(rateLimiter)
                .checkOrThrow(eq(RateLimitPolicies.NONCE_IP), eq("203.0.113.9"));

        ingressRateLimitFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("12");
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo(String.valueOf(RateLimitPolicies.NONCE_IP.limit()));
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getHeader("X-RateLimit-Reset")).isEqualTo("1710000000");
    }
}

package org.example.ticket.util.ratelimit;

import org.example.ticket.util.tracing.TracingConstants;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitExceptionHandlerTest {

    private final RateLimitExceptionHandler handler = new RateLimitExceptionHandler();

    @Test
    void returns429HeadersAndCorrelationIdBody() {
        RateLimitPolicy policy = new RateLimitPolicy("nonce.ip", "/api/user/nonce", "ip", 5, Duration.ofSeconds(60));
        RateLimitDecision decision = RateLimitDecision.rejected(policy, 0L, 25L, 1_777_777_777L, "limit_exceeded");
        MDC.put(TracingConstants.CORRELATION_ID_MDC_KEY, "trace-123");

        try {
            ResponseEntity<RateLimitErrorResponse> response = handler.handle(new RateLimitException(decision));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("25");
            assertThat(response.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("5");
            assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
            assertThat(response.getHeaders().getFirst("X-RateLimit-Reset")).isEqualTo("1777777777");
            assertThat(response.getBody()).isEqualTo(new RateLimitErrorResponse(
                    "RATE_LIMITED",
                    "Too many requests",
                    "nonce.ip",
                    25L,
                    "trace-123"
            ));
        } finally {
            MDC.clear();
        }
    }
}

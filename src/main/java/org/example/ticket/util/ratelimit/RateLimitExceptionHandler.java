package org.example.ticket.util.ratelimit;

import org.example.ticket.util.tracing.TracingConstants;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RateLimitExceptionHandler {

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<RateLimitErrorResponse> handle(RateLimitException exception) {
        RateLimitDecision decision = exception.getDecision();

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        headers.add("X-RateLimit-Limit", String.valueOf(decision.limit()));
        headers.add("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        headers.add("X-RateLimit-Reset", String.valueOf(decision.resetEpochSeconds()));

        RateLimitErrorResponse body = new RateLimitErrorResponse(
                "RATE_LIMITED",
                "Too many requests",
                decision.policy(),
                decision.retryAfterSeconds(),
                MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)
        );

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers)
                .body(body);
    }
}

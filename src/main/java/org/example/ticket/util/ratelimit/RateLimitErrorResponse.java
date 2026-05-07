package org.example.ticket.util.ratelimit;

public record RateLimitErrorResponse(
        String code,
        String message,
        String policy,
        long retryAfterSeconds,
        String correlationId
) {
}

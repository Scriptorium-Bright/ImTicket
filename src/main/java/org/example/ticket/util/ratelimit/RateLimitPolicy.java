package org.example.ticket.util.ratelimit;

import java.time.Duration;

public record RateLimitPolicy(
        String policyName,
        String endpoint,
        String keyType,
        long limit,
        Duration window
) {

    public RateLimitPolicy {
        if (policyName == null || policyName.isBlank()) {
            throw new IllegalArgumentException("policyName must not be blank");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        if (keyType == null || keyType.isBlank()) {
            throw new IllegalArgumentException("keyType must not be blank");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
    }
}

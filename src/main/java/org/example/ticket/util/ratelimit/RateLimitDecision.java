package org.example.ticket.util.ratelimit;

public record RateLimitDecision(
        boolean allowed,
        String policy,
        String endpoint,
        String keyType,
        long limit,
        long remaining,
        long retryAfterSeconds,
        long resetEpochSeconds,
        String reason
) {

    public static RateLimitDecision allowed(RateLimitPolicy policy, long remaining, long retryAfterSeconds,
            long resetEpochSeconds) {
        return new RateLimitDecision(
                true,
                policy.policyName(),
                policy.endpoint(),
                policy.keyType(),
                policy.limit(),
                remaining,
                retryAfterSeconds,
                resetEpochSeconds,
                "allowed"
        );
    }

    public static RateLimitDecision rejected(RateLimitPolicy policy, long remaining, long retryAfterSeconds,
            long resetEpochSeconds, String reason) {
        return new RateLimitDecision(
                false,
                policy.policyName(),
                policy.endpoint(),
                policy.keyType(),
                policy.limit(),
                remaining,
                retryAfterSeconds,
                resetEpochSeconds,
                reason
        );
    }
}

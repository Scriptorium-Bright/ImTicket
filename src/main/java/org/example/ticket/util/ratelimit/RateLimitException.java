package org.example.ticket.util.ratelimit;

public class RateLimitException extends RuntimeException {

    private final RateLimitDecision decision;

    public RateLimitException(RateLimitDecision decision) {
        super("Rate limit exceeded for policy " + decision.policy());
        this.decision = decision;
    }

    public RateLimitDecision getDecision() {
        return decision;
    }
}

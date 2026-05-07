package org.example.ticket.util.ratelimit;

import java.time.Duration;

public final class RateLimitPolicies {

    public static final RateLimitPolicy SMS_CERTIFICATE_IP = new RateLimitPolicy(
            "sms.certificate.ip",
            "POST /api/sms/certificate",
            "ip",
            5,
            Duration.ofMinutes(1)
    );

    public static final RateLimitPolicy SMS_VERIFY_IP = new RateLimitPolicy(
            "sms.verify.ip",
            "POST /api/sms/verify",
            "ip",
            10,
            Duration.ofMinutes(1)
    );

    public static final RateLimitPolicy NONCE_IP = new RateLimitPolicy(
            "nonce.ip",
            "GET /api/user/nonce",
            "ip",
            20,
            Duration.ofMinutes(1)
    );

    public static final RateLimitPolicy SIGNATURE_VERIFY_IP = new RateLimitPolicy(
            "signature.verify.ip",
            "POST /api/user/signature/verify",
            "ip",
            10,
            Duration.ofMinutes(1)
    );

    public static final RateLimitPolicy ENTRY_VERIFY_IP = new RateLimitPolicy(
            "entry.verify.ip",
            "POST /api/entry/verify",
            "ip",
            30,
            Duration.ofMinutes(1)
    );

    private RateLimitPolicies() {
    }
}

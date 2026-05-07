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

    public static final RateLimitPolicy SMS_CERTIFICATE_PHONE_IP = new RateLimitPolicy(
            "sms.certificate.phone_ip",
            "POST /api/sms/certificate",
            "phone_ip",
            3,
            Duration.ofMinutes(1)
    );

    public static final RateLimitPolicy SMS_VERIFY_PHONE_IP = new RateLimitPolicy(
            "sms.verify.phone_ip",
            "POST /api/sms/verify",
            "phone_ip",
            8,
            Duration.ofMinutes(1)
    );

    public static final RateLimitPolicy NONCE_WALLET_IP = new RateLimitPolicy(
            "nonce.wallet_ip",
            "GET /api/user/nonce",
            "wallet_ip",
            10,
            Duration.ofMinutes(1)
    );

    public static final RateLimitPolicy SIGNATURE_VERIFY_WALLET_IP = new RateLimitPolicy(
            "signature.verify.wallet_ip",
            "POST /api/user/signature/verify",
            "wallet_ip",
            8,
            Duration.ofMinutes(1)
    );

    public static final RateLimitPolicy ENTRY_VERIFY_GATE = new RateLimitPolicy(
            "entry.verify.gate",
            "POST /api/entry/verify",
            "gate_name",
            20,
            Duration.ofSeconds(30)
    );

    public static final RateLimitPolicy ENTRY_VERIFY_BUSINESS_IP = new RateLimitPolicy(
            "entry.verify.business.ip",
            "POST /api/entry/verify",
            "ip",
            20,
            Duration.ofSeconds(30)
    );

    public static final RateLimitPolicy PRE_RESERVE_WALLET_PERFORMANCE = new RateLimitPolicy(
            "reservation.pre_reserve.wallet_performance",
            "POST /api/reservation/pre-reserve",
            "wallet_performance",
            4,
            Duration.ofSeconds(30)
    );

    public static final RateLimitPolicy PRE_RESERVE_DUPLICATE = new RateLimitPolicy(
            "reservation.pre_reserve.duplicate",
            "POST /api/reservation/pre-reserve",
            "wallet_performance_seats",
            1,
            Duration.ofSeconds(10)
    );

    private RateLimitPolicies() {
    }
}

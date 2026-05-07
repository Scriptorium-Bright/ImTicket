package org.example.ticket.util.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class BusinessRateLimitGuard {

    private static final String SMS_VERIFY_FAILURE_PREFIX = "rl:sms:verify:failure:";
    private static final String SMS_VERIFY_BLOCK_PREFIX = "rl:sms:verify:block:";
    private static final Duration SMS_VERIFY_FAILURE_WINDOW = Duration.ofMinutes(5);
    private static final Duration SMS_VERIFY_BLOCK_WINDOW = Duration.ofMinutes(5);
    private static final int SMS_VERIFY_FAILURE_THRESHOLD = 5;

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final StringRedisTemplate stringRedisTemplate;

    public void checkSmsCertificate(String phoneNumber, String clientIp) {
        String normalizedKey = phoneIpKey(phoneNumber, clientIp);
        rateLimiter.checkOrThrow(RateLimitPolicies.SMS_CERTIFICATE_PHONE_IP, normalizedKey);
    }

    public void checkSmsVerifyRequest(String phoneNumber, String clientIp) {
        String normalizedKey = phoneIpKey(phoneNumber, clientIp);
        ensureSmsVerifyNotBlocked(normalizedKey);
        rateLimiter.checkOrThrow(RateLimitPolicies.SMS_VERIFY_PHONE_IP, normalizedKey);
    }

    public void recordSmsVerifyResult(String phoneNumber, String clientIp, boolean success) {
        String normalizedKey = phoneIpKey(phoneNumber, clientIp);
        String failureKey = SMS_VERIFY_FAILURE_PREFIX + normalizedKey;
        String blockKey = SMS_VERIFY_BLOCK_PREFIX + normalizedKey;

        if (success) {
            stringRedisTemplate.delete(failureKey);
            stringRedisTemplate.delete(blockKey);
            return;
        }

        Long failureCount = stringRedisTemplate.opsForValue().increment(failureKey);
        if (failureCount != null && failureCount == 1L) {
            stringRedisTemplate.expire(failureKey, SMS_VERIFY_FAILURE_WINDOW);
        }

        if (failureCount != null && failureCount >= SMS_VERIFY_FAILURE_THRESHOLD) {
            stringRedisTemplate.opsForValue().set(blockKey, "BLOCKED", SMS_VERIFY_BLOCK_WINDOW);
        }
    }

    public void checkNonceRequest(String walletAddress, String clientIp) {
        String normalizedKey = walletIpKey(walletAddress, clientIp);
        rateLimiter.checkOrThrow(RateLimitPolicies.NONCE_WALLET_IP, normalizedKey);
    }

    public void checkSignatureVerify(String walletAddress, String clientIp) {
        String normalizedKey = walletIpKey(walletAddress, clientIp);
        rateLimiter.checkOrThrow(RateLimitPolicies.SIGNATURE_VERIFY_WALLET_IP, normalizedKey);
    }

    public void checkEntryVerify(String gateName, String clientIp) {
        if (gateName != null && !gateName.isBlank()) {
            String normalizedGateName = normalizeGateName(gateName);
            rateLimiter.checkOrThrow(RateLimitPolicies.ENTRY_VERIFY_GATE, normalizedGateName);
            return;
        }

        String normalizedIp = clientIpResolver.normalizeIp(clientIp);
        rateLimiter.checkOrThrow(RateLimitPolicies.ENTRY_VERIFY_BUSINESS_IP, normalizedIp);
    }

    public String resolveClientIp(jakarta.servlet.http.HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    private void ensureSmsVerifyNotBlocked(String normalizedKey) {
        String blockKey = SMS_VERIFY_BLOCK_PREFIX + normalizedKey;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(blockKey))) {
            RateLimitDecision decision = RateLimitDecision.rejected(
                    RateLimitPolicies.SMS_VERIFY_PHONE_IP,
                    0,
                    SMS_VERIFY_BLOCK_WINDOW.toSeconds(),
                    currentResetEpochSeconds(SMS_VERIFY_BLOCK_WINDOW),
                    "temporarily_blocked"
            );
            throw new RateLimitException(decision);
        }
    }

    private long currentResetEpochSeconds(Duration duration) {
        return (System.currentTimeMillis() / 1000L) + duration.toSeconds();
    }

    private String phoneIpKey(String phoneNumber, String clientIp) {
        return clientIpResolver.normalizePhone(phoneNumber) + ":" + clientIpResolver.normalizeIp(clientIp);
    }

    private String walletIpKey(String walletAddress, String clientIp) {
        return clientIpResolver.normalizeWallet(walletAddress) + ":" + clientIpResolver.normalizeIp(clientIp);
    }

    private String normalizeGateName(String gateName) {
        return gateName.trim().toLowerCase();
    }
}

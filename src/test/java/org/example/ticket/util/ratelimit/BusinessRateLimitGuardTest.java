package org.example.ticket.util.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessRateLimitGuardTest {

    @Mock
    private RedisFixedWindowRateLimiter rateLimiter;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private BusinessRateLimitGuard businessRateLimitGuard;

    @BeforeEach
    void setUp() {
        ClientIpResolver clientIpResolver = new ClientIpResolver();
        businessRateLimitGuard = new BusinessRateLimitGuard(rateLimiter, clientIpResolver, stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void checkSmsCertificateUsesNormalizedPhoneAndIpKey() {
        businessRateLimitGuard.checkSmsCertificate("010-1234-5678", "203.0.113.10");

        verify(rateLimiter).checkOrThrow(
                eq(RateLimitPolicies.SMS_CERTIFICATE_PHONE_IP),
                eq("01012345678:203.0.113.10")
        );
    }

    @Test
    void checkSmsVerifyRejectsBlockedPhoneIpBeforeRateLimiter() {
        when(stringRedisTemplate.hasKey("rl:sms:verify:block:01012345678:203.0.113.10")).thenReturn(true);

        assertThatThrownBy(() -> businessRateLimitGuard.checkSmsVerifyRequest("01012345678", "203.0.113.10"))
                .isInstanceOf(RateLimitException.class)
                .hasMessageContaining("Rate limit exceeded");

        verify(rateLimiter, never()).checkOrThrow(eq(RateLimitPolicies.SMS_VERIFY_PHONE_IP), eq("01012345678:203.0.113.10"));
    }

    @Test
    void recordSmsVerifyFailureBlocksPhoneIpAfterThreshold() {
        when(valueOperations.increment("rl:sms:verify:failure:01012345678:203.0.113.10")).thenReturn(5L);

        businessRateLimitGuard.recordSmsVerifyResult("01012345678", "203.0.113.10", false);

        verify(valueOperations).set(
                eq("rl:sms:verify:block:01012345678:203.0.113.10"),
                eq("BLOCKED"),
                eq(Duration.ofMinutes(5))
        );
    }

    @Test
    void checkNonceRequestUsesNormalizedWalletAndIpKey() {
        businessRateLimitGuard.checkNonceRequest("0xABCD", "198.51.100.4");

        verify(rateLimiter).checkOrThrow(
                eq(RateLimitPolicies.NONCE_WALLET_IP),
                eq("0xabcd:198.51.100.4")
        );
    }

    @Test
    void checkEntryVerifyFallsBackToIpWhenGateNameIsBlank() {
        businessRateLimitGuard.checkEntryVerify("   ", "198.51.100.9");

        verify(rateLimiter).checkOrThrow(
                eq(RateLimitPolicies.ENTRY_VERIFY_BUSINESS_IP),
                eq("198.51.100.9")
        );
    }
}

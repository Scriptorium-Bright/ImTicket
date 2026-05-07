package org.example.ticket.util.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PreReserveAdmissionController {

    private static final int MAX_SLOTS = 16;
    private static final Duration LEASE_TTL = Duration.ofSeconds(10);
    private static final String ADMISSION_PREFIX = "adm:pre-reserve:";

    private final StringRedisTemplate stringRedisTemplate;

    public AdmissionLease acquire(Long performanceTimeId) {
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            String key = ADMISSION_PREFIX + performanceTimeId + ":slot:" + slot;
            String token = UUID.randomUUID().toString();

            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(key, token, LEASE_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                return new AdmissionLease(key, token, performanceTimeId, slot);
            }
        }

        log.warn(
                "Admission rejected. correlationId={}, policy={}, keyType={}, keyHash={}, remaining={}, retryAfter={}, reason={}",
                RateLimitLogSupport.correlationId(),
                RateLimitPolicies.PRE_RESERVE_ADMISSION.policyName(),
                RateLimitPolicies.PRE_RESERVE_ADMISSION.keyType(),
                RateLimitLogSupport.redactKey(String.valueOf(performanceTimeId)),
                0,
                LEASE_TTL.toSeconds(),
                "admission_full"
        );
        RateLimitDecision decision = RateLimitDecision.rejected(
                RateLimitPolicies.PRE_RESERVE_ADMISSION,
                0,
                LEASE_TTL.toSeconds(),
                (System.currentTimeMillis() / 1000L) + LEASE_TTL.toSeconds(),
                "admission_full"
        );
        throw new RateLimitException(decision);
    }

    public void release(AdmissionLease lease) {
        if (lease == null) {
            return;
        }

        String currentToken = stringRedisTemplate.opsForValue().get(lease.key());
        if (lease.token().equals(currentToken)) {
            stringRedisTemplate.delete(lease.key());
        }
    }

    public record AdmissionLease(String key, String token, Long performanceTimeId, int slot) {
    }
}

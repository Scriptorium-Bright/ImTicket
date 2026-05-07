package org.example.ticket.util.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.reservation.request.ReservationRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class PreReserveGuard {

    private static final String DEDUPE_PREFIX = "dedupe:pre-reserve:";
    private static final Duration DEDUPE_TTL = Duration.ofSeconds(10);

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final StringRedisTemplate stringRedisTemplate;
    private final PreReserveAdmissionController preReserveAdmissionController;

    public PreReserveExecution begin(String walletAddress, ReservationRequest request) {
        String normalizedWallet = clientIpResolver.normalizeWallet(walletAddress);
        Long performanceTimeId = request.getPerformanceTimeId();

        if (performanceTimeId == null) {
            throw new IllegalArgumentException("performanceTimeId must not be null");
        }

        String walletPerformanceKey = normalizedWallet + ":" + performanceTimeId;
        rateLimiter.checkOrThrow(RateLimitPolicies.PRE_RESERVE_WALLET_PERFORMANCE, walletPerformanceKey);

        String dedupeKey = buildDedupeKey(normalizedWallet, performanceTimeId, request.getSeatIds());
        Boolean dedupeAcquired = stringRedisTemplate.opsForValue().setIfAbsent(dedupeKey, "IN_PROGRESS", DEDUPE_TTL);
        if (!Boolean.TRUE.equals(dedupeAcquired)) {
            log.warn(
                    "Pre-reserve duplicate rejected. correlationId={}, policy={}, keyType={}, keyHash={}, remaining={}, retryAfter={}, reason={}",
                    RateLimitLogSupport.correlationId(),
                    RateLimitPolicies.PRE_RESERVE_DUPLICATE.policyName(),
                    RateLimitPolicies.PRE_RESERVE_DUPLICATE.keyType(),
                    RateLimitLogSupport.redactKey(dedupeKey),
                    0,
                    DEDUPE_TTL.toSeconds(),
                    "duplicate_in_progress"
            );
            RateLimitDecision decision = RateLimitDecision.rejected(
                    RateLimitPolicies.PRE_RESERVE_DUPLICATE,
                    0,
                    DEDUPE_TTL.toSeconds(),
                    (System.currentTimeMillis() / 1000L) + DEDUPE_TTL.toSeconds(),
                    "duplicate_in_progress"
            );
            throw new RateLimitException(decision);
        }

        PreReserveAdmissionController.AdmissionLease admissionLease =
                preReserveAdmissionController.acquire(performanceTimeId);

        return new PreReserveExecution(dedupeKey, admissionLease);
    }

    private String buildDedupeKey(String normalizedWallet, Long performanceTimeId, List<Long> seatIds) {
        String sortedSeatIds = seatIds.stream()
                .sorted(Comparator.naturalOrder())
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        return DEDUPE_PREFIX + normalizedWallet + ":" + performanceTimeId + ":" + sortedSeatIds;
    }

    public final class PreReserveExecution implements AutoCloseable {

        private final String dedupeKey;
        private final PreReserveAdmissionController.AdmissionLease admissionLease;
        private boolean success;

        private PreReserveExecution(String dedupeKey,
                PreReserveAdmissionController.AdmissionLease admissionLease) {
            this.dedupeKey = dedupeKey;
            this.admissionLease = admissionLease;
        }

        public void markSuccess() {
            this.success = true;
        }

        @Override
        public void close() {
            preReserveAdmissionController.release(admissionLease);
            if (!success) {
                stringRedisTemplate.delete(dedupeKey);
            }
        }
    }
}

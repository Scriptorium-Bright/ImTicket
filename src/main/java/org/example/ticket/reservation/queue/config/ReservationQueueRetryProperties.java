package org.example.ticket.reservation.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Queue 재시도 횟수, backoff와 한 번의 승격량을 묶은 설정이다. */
@ConfigurationProperties(prefix = "reservation.queue.retry")
public record ReservationQueueRetryProperties(
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("500ms") Duration initialBackoff,
        @DefaultValue("30s") Duration maxBackoff,
        @DefaultValue("50") int promotionBatchSize
) {

    /**
     * 재시도 횟수와 시간 설정의 양수 조건을 검증한다.
     * 최대 backoff가 최초 backoff보다 짧은 구성도 시작 전에 거절한다.
     */
    @ConstructorBinding
    public ReservationQueueRetryProperties {
        if (maxAttempts <= 0 || promotionBatchSize <= 0) {
            throw new IllegalArgumentException("Retry counts must be positive");
        }
        requirePositive(initialBackoff, "initialBackoff");
        requirePositive(maxBackoff, "maxBackoff");
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must be at least initialBackoff");
        }
    }

    /**
     * 완료된 실패 횟수에 지수 backoff를 적용한다.
     * overflow와 과도한 지연은 max backoff에서 제한한다.
     */
    public Duration backoffFor(int failedAttempt) {
        if (failedAttempt <= 0) {
            throw new IllegalArgumentException("failedAttempt must be positive");
        }
        long multiplier = 1L << Math.min(failedAttempt - 1, 30);
        long initialMillis = initialBackoff.toMillis();
        long candidate = initialMillis > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE
                : initialMillis * multiplier;
        return Duration.ofMillis(Math.min(candidate, maxBackoff.toMillis()));
    }

    /**
     * Duration 설정이 null이 아니며 양수인지 확인한다.
     * 0 또는 음수 지연으로 busy retry가 시작되는 구성을 막는다.
     */
    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

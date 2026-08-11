package org.example.ticket.reservation.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Queue Stream Worker의 identity, 동시성과 polling 상한을 묶은 설정이다. */
@ConfigurationProperties(prefix = "reservation.queue.worker")
public record ReservationQueueWorkerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("reservation-booking-workers") String consumerGroup,
        @DefaultValue("local-worker") String instanceId,
        @DefaultValue("1") int concurrency,
        @DefaultValue("1") int perPerformanceConcurrency,
        @DefaultValue("100ms") Duration readBlockTimeout,
        @DefaultValue("100ms") Duration pollInterval
) {

    /**
     * Worker identity와 처리량 설정의 유효 범위를 검증한다.
     * 회차별 상한이 전체 상한보다 커지는 구성은 시작 단계에서 거절한다.
     */
    @ConstructorBinding
    public ReservationQueueWorkerProperties {
        requireText(consumerGroup, "consumerGroup");
        requireText(instanceId, "instanceId");
        requirePositive(concurrency, "concurrency");
        requirePositive(perPerformanceConcurrency, "perPerformanceConcurrency");
        requirePositive(readBlockTimeout, "readBlockTimeout");
        requirePositive(pollInterval, "pollInterval");
        if (perPerformanceConcurrency > concurrency) {
            throw new IllegalArgumentException("perPerformanceConcurrency must not exceed concurrency");
        }
    }

    /**
     * 문자열 설정이 null 또는 공백인지 확인한다.
     * Consumer Group과 instance ID가 모호한 상태로 시작되는 것을 막는다.
     */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * 정수 처리량 설정이 양수인지 확인한다.
     * 0 이하의 값이 executor와 semaphore에 전달되는 것을 막는다.
     */
    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /**
     * 시간 설정이 null이 아니며 양수인지 확인한다.
     * 무한 polling이나 busy loop를 만드는 값을 시작 전에 거절한다.
     */
    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

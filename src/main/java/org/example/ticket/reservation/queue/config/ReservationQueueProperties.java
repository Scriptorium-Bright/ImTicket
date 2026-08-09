package org.example.ticket.reservation.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 예약 대기열의 admission 한도와 ticket 보존 시간을 묶은 설정이다. */
@ConfigurationProperties(prefix = "reservation.queue")
public record ReservationQueueProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1000") int maxDepth,
        @DefaultValue("10m") Duration maxWait,
        @DefaultValue("30m") Duration ticketRetention,
        @DefaultValue("60m") Duration idempotencyRetention,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("1s") Duration expiryScanInterval,
        @DefaultValue("200") int expiryBatchSize,
        @DefaultValue("30s") Duration processingLease
) {

    /**
     * Queue 설정값의 양수 조건과 보존 시간 순서를 검증한다.
     * 잘못된 용량이나 TTL 설정이면 애플리케이션 시작 단계에서 실패시킨다.
     */
    @ConstructorBinding
    public ReservationQueueProperties {
        requirePositive(maxDepth, "maxDepth");
        requirePositive(maxWait, "maxWait");
        requirePositive(ticketRetention, "ticketRetention");
        requirePositive(idempotencyRetention, "idempotencyRetention");
        requirePositive(pollInterval, "pollInterval");
        requirePositive(expiryScanInterval, "expiryScanInterval");
        requirePositive(expiryBatchSize, "expiryBatchSize");
        requirePositive(processingLease, "processingLease");
        if (ticketRetention.compareTo(maxWait) < 0) {
            throw new IllegalArgumentException("ticketRetention must be at least maxWait");
        }
        if (idempotencyRetention.compareTo(ticketRetention) < 0) {
            throw new IllegalArgumentException("idempotencyRetention must be at least ticketRetention");
        }
    }

    /**
     * 핵심 admission과 보존 설정만 지정하는 간편 생성자다.
     * polling과 만료 scan에는 기본 주기와 batch 크기를 적용한다.
     */
    public ReservationQueueProperties(
            boolean enabled,
            int maxDepth,
            Duration maxWait,
            Duration ticketRetention,
            Duration idempotencyRetention
    ) {
        this(
                enabled,
                maxDepth,
                maxWait,
                ticketRetention,
                idempotencyRetention,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                200,
                Duration.ofSeconds(30)
        );
    }

    /**
     * 비활성 상태의 안전한 Queue 기본 설정을 만든다.
     * 단위 테스트와 명시적 수동 구성에서 공통 초기값으로 사용한다.
     */
    public static ReservationQueueProperties defaults() {
        return new ReservationQueueProperties(
                false,
                1_000,
                Duration.ofMinutes(10),
                Duration.ofMinutes(30),
                Duration.ofMinutes(60),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                200,
                Duration.ofSeconds(30)
        );
    }

    /**
     * 정수 설정값이 0보다 큰지 확인한다.
     * 위반 시 설정 이름을 포함한 초기화 예외를 발생시킨다.
     */
    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /**
     * 시간 설정값이 null이 아니고 양수인지 확인한다.
     * 0 또는 음수 duration이 Queue 반복 작업에 전달되는 것을 막는다.
     */
    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

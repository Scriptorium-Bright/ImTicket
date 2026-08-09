package org.example.ticket.reservation.queue;

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
        @DefaultValue("200") int expiryBatchSize
) {

    @ConstructorBinding
    public ReservationQueueProperties {
        requirePositive(maxDepth, "maxDepth");
        requirePositive(maxWait, "maxWait");
        requirePositive(ticketRetention, "ticketRetention");
        requirePositive(idempotencyRetention, "idempotencyRetention");
        requirePositive(pollInterval, "pollInterval");
        requirePositive(expiryScanInterval, "expiryScanInterval");
        requirePositive(expiryBatchSize, "expiryBatchSize");
        if (ticketRetention.compareTo(maxWait) < 0) {
            throw new IllegalArgumentException("ticketRetention must be at least maxWait");
        }
        if (idempotencyRetention.compareTo(ticketRetention) < 0) {
            throw new IllegalArgumentException("idempotencyRetention must be at least ticketRetention");
        }
    }

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
                200
        );
    }

    public static ReservationQueueProperties defaults() {
        return new ReservationQueueProperties(
                false,
                1_000,
                Duration.ofMinutes(10),
                Duration.ofMinutes(30),
                Duration.ofMinutes(60),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                200
        );
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

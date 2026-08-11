package org.example.ticket.reservation.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Queue mapping 복구와 retention cleanup의 주기와 batch 상한 설정이다. */
@ConfigurationProperties(prefix = "reservation.queue.maintenance")
public record ReservationQueueMaintenanceProperties(
        @DefaultValue("30s") Duration enqueuingTimeout,
        @DefaultValue("5s") Duration interval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("200") int scanCount
) {

    /**
     * Maintenance 시간과 batch 설정이 모두 양수인지 확인한다.
     * Busy scan과 무제한 정리를 만드는 값을 시작 전에 거절한다.
     */
    @ConstructorBinding
    public ReservationQueueMaintenanceProperties {
        requirePositive(enqueuingTimeout, "enqueuingTimeout");
        requirePositive(interval, "interval");
        if (batchSize <= 0 || scanCount <= 0) {
            throw new IllegalArgumentException("Maintenance limits must be positive");
        }
    }

    /**
     * Duration 설정이 null이 아니며 양수인지 확인한다.
     * 0 이하 주기와 timeout이 scheduler에 전달되는 것을 막는다.
     */
    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

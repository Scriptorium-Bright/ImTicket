package org.example.ticket.reservation.queue.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationQueueMaintenancePropertiesTest {

    @Test
    void zeroOrNegativeMaintenanceLimitsAreRejected() {
        assertThatThrownBy(() -> new ReservationQueueMaintenanceProperties(
                Duration.ZERO, Duration.ofSeconds(1), 10, 10
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationQueueMaintenanceProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, 10
        )).isInstanceOf(IllegalArgumentException.class);
    }
}

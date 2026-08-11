package org.example.ticket.reservation.queue.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationQueueRetryPropertiesTest {

    @Test
    void exponentialBackoffStopsAtConfiguredMaximum() {
        ReservationQueueRetryProperties properties = new ReservationQueueRetryProperties(
                5, Duration.ofMillis(100), Duration.ofMillis(250), 10
        );

        assertThat(properties.backoffFor(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(properties.backoffFor(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(properties.backoffFor(3)).isEqualTo(Duration.ofMillis(250));
        assertThat(properties.backoffFor(10)).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void invalidRetryConfigurationIsRejectedAtStartupBoundary() {
        assertThatThrownBy(() -> new ReservationQueueRetryProperties(
                0, Duration.ofMillis(100), Duration.ofSeconds(1), 10
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationQueueRetryProperties(
                3, Duration.ofSeconds(2), Duration.ofSeconds(1), 10
        )).isInstanceOf(IllegalArgumentException.class);
    }
}

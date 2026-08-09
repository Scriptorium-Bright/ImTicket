package org.example.ticket.reservation.queue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationQueuePropertiesTest {

    @Test
    void defaultsKeepQueueDisabledAndRetentionsLongerThanMaximumWait() {
        ReservationQueueProperties properties = ReservationQueueProperties.defaults();

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.maxDepth()).isEqualTo(1_000);
        assertThat(properties.maxWait()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.ticketRetention()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.idempotencyRetention()).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void rejectsInvalidDepthAndRetentionRelationships() {
        assertThatThrownBy(() -> new ReservationQueueProperties(
                false, 0, Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofMinutes(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDepth");

        assertThatThrownBy(() -> new ReservationQueueProperties(
                false, 100, Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofMinutes(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ticketRetention");

        assertThatThrownBy(() -> new ReservationQueueProperties(
                false, 100, Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofMinutes(20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyRetention");
    }
}

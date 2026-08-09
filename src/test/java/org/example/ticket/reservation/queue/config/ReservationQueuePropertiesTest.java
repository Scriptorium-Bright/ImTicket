package org.example.ticket.reservation.queue.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

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
        assertThat(properties.pollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.expiryScanInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.expiryBatchSize()).isEqualTo(200);
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

    @Test
    void bindsDarkLaunchOverridesFromSpringEnvironment() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "reservation.queue.enabled", "true",
                "reservation.queue.max-depth", "2000",
                "reservation.queue.expiry-batch-size", "500"
        ));

        ReservationQueueProperties properties = new Binder(source)
                .bind("reservation.queue", Bindable.of(ReservationQueueProperties.class))
                .orElseThrow(() -> new AssertionError("reservation.queue binding failed"));

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.maxDepth()).isEqualTo(2_000);
        assertThat(properties.expiryBatchSize()).isEqualTo(500);
        assertThat(properties.maxWait()).isEqualTo(Duration.ofMinutes(10));
    }
}

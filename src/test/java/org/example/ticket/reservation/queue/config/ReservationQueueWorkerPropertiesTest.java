package org.example.ticket.reservation.queue.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationQueueWorkerPropertiesTest {

    @Test
    void bindsBoundedWorkerSettings() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "reservation.queue.worker.enabled", "true",
                "reservation.queue.worker.consumer-group", "booking-workers",
                "reservation.queue.worker.instance-id", "worker-a",
                "reservation.queue.worker.concurrency", "2",
                "reservation.queue.worker.per-performance-concurrency", "1",
                "reservation.queue.worker.read-block-timeout", "250ms",
                "reservation.queue.worker.poll-interval", "50ms"
        ));

        ReservationQueueWorkerProperties properties = new Binder(source)
                .bind("reservation.queue.worker", Bindable.of(ReservationQueueWorkerProperties.class))
                .orElseThrow(() -> new AssertionError("worker properties binding failed"));

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.consumerGroup()).isEqualTo("booking-workers");
        assertThat(properties.instanceId()).isEqualTo("worker-a");
        assertThat(properties.concurrency()).isEqualTo(2);
        assertThat(properties.perPerformanceConcurrency()).isEqualTo(1);
        assertThat(properties.readBlockTimeout()).isEqualTo(Duration.ofMillis(250));
        assertThat(properties.pollInterval()).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void rejectsUnboundedOrAmbiguousWorkerIdentitySettings() {
        assertThatThrownBy(() -> new ReservationQueueWorkerProperties(
                true, " ", "worker-a", 1, 1, Duration.ofMillis(100), Duration.ofMillis(100)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consumerGroup");

        assertThatThrownBy(() -> new ReservationQueueWorkerProperties(
                true, "booking-workers", "worker-a", 1, 2,
                Duration.ofMillis(100), Duration.ofMillis(100)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("perPerformanceConcurrency");
    }

    @Test
    void workerExecutorUsesConcurrencyAsThreadLimitAndHasNoWaitingQueue() {
        ReservationQueueConfiguration configuration = new ReservationQueueConfiguration();
        ReservationQueueWorkerProperties properties = new ReservationQueueWorkerProperties(
                true, "booking-workers", "worker-a", 2, 1,
                Duration.ofMillis(100), Duration.ofMillis(100)
        );

        ThreadPoolExecutor executor = configuration.reservationQueueWorkerExecutor(properties);
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaximumPoolSize()).isEqualTo(2);
            assertThat(executor.getQueue()).isInstanceOf(SynchronousQueue.class).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }
}

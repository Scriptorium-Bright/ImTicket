package org.example.ticket.reservation.queue.service;

import org.example.ticket.reservation.queue.config.ReservationQueueMaintenanceProperties;
import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.config.ReservationQueueWorkerProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueMappingRepairResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueTerminalCleanupResult;
import org.example.ticket.reservation.queue.repository.ReservationQueueExpiryIndex;
import org.example.ticket.reservation.queue.repository.ReservationQueueMaintenanceStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservationQueueMaintenanceServiceTest {

    @Test
    void mappingAndActiveRepairRunBeforeBoundedTerminalCleanup() {
        ReservationQueueMaintenanceStore store = mock(ReservationQueueMaintenanceStore.class);
        ReservationQueueExpiryIndex expiryIndex = mock(ReservationQueueExpiryIndex.class);
        ReservationQueueProperties queueProperties = ReservationQueueProperties.defaults();
        ReservationQueueWorkerProperties workerProperties = new ReservationQueueWorkerProperties(
                true, "booking-workers", "worker-a", 1, 1,
                Duration.ofMillis(10), Duration.ofMillis(10)
        );
        ReservationQueueMaintenanceProperties maintenanceProperties =
                new ReservationQueueMaintenanceProperties(
                        Duration.ofSeconds(30), Duration.ofSeconds(5), 10, 20
                );
        ReservationQueueMaintenanceService service = new ReservationQueueMaintenanceService(
                store, expiryIndex, queueProperties, workerProperties, maintenanceProperties
        );
        Instant now = Instant.parse("2026-08-12T10:00:00Z");
        when(store.reconcileStaleMappings(now.minusSeconds(30), now, 10))
                .thenReturn(new ReservationQueueMappingRepairResult(2, 1, 0));
        when(store.repairActivePerformances(now, 20)).thenReturn(1);
        when(expiryIndex.activePerformanceTimeIds()).thenReturn(List.of(42L, 43L));
        when(store.cleanupTerminalTickets(
                42L,
                "booking-workers",
                now.minus(queueProperties.idempotencyRetention()),
                10
        )).thenReturn(new ReservationQueueTerminalCleanupResult(7, 3));
        when(store.cleanupTerminalTickets(
                43L,
                "booking-workers",
                now.minus(queueProperties.idempotencyRetention()),
                3
        )).thenReturn(new ReservationQueueTerminalCleanupResult(3, 1));

        var result = service.runOnce(now);

        assertThat(result.mappingsRepaired()).isEqualTo(2);
        assertThat(result.mappingsReleased()).isEqualTo(1);
        assertThat(result.activePerformancesRepaired()).isEqualTo(1);
        assertThat(result.terminalTicketsCleaned()).isEqualTo(4);
        var order = inOrder(store, expiryIndex);
        order.verify(store).reconcileStaleMappings(now.minusSeconds(30), now, 10);
        order.verify(store).repairActivePerformances(now, 20);
        order.verify(expiryIndex).activePerformanceTimeIds();
        order.verify(store).cleanupTerminalTickets(
                42L,
                "booking-workers",
                now.minus(queueProperties.idempotencyRetention()),
                10
        );
        order.verify(store).cleanupTerminalTickets(
                43L,
                "booking-workers",
                now.minus(queueProperties.idempotencyRetention()),
                3
        );
        order.verify(expiryIndex).removeStalePerformanceTimes(now);
    }
}

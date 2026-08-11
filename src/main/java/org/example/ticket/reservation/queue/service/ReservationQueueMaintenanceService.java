package org.example.ticket.reservation.queue.service;

import org.example.ticket.reservation.queue.config.ReservationQueueMaintenanceProperties;
import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.config.ReservationQueueWorkerProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueMaintenanceResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueMappingRepairResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueTerminalCleanupResult;
import org.example.ticket.reservation.queue.repository.ReservationQueueExpiryIndex;
import org.example.ticket.reservation.queue.repository.ReservationQueueMaintenanceStore;

import java.time.Instant;
import java.util.Objects;

/** Queue stale mapping, active registry와 terminal retention 정리를 조율한다. */
public final class ReservationQueueMaintenanceService {

    private final ReservationQueueMaintenanceStore maintenanceStore;
    private final ReservationQueueExpiryIndex expiryIndex;
    private final ReservationQueueProperties queueProperties;
    private final ReservationQueueWorkerProperties workerProperties;
    private final ReservationQueueMaintenanceProperties maintenanceProperties;

    /**
     * Maintenance 저장소, active index와 보존 정책을 연결한다.
     * 한 tick의 전체 terminal cleanup이 batch 상한을 넘지 않게 구성한다.
     */
    public ReservationQueueMaintenanceService(
            ReservationQueueMaintenanceStore maintenanceStore,
            ReservationQueueExpiryIndex expiryIndex,
            ReservationQueueProperties queueProperties,
            ReservationQueueWorkerProperties workerProperties,
            ReservationQueueMaintenanceProperties maintenanceProperties
    ) {
        this.maintenanceStore = Objects.requireNonNull(maintenanceStore, "maintenanceStore must not be null");
        this.expiryIndex = Objects.requireNonNull(expiryIndex, "expiryIndex must not be null");
        this.queueProperties = Objects.requireNonNull(queueProperties, "queueProperties must not be null");
        this.workerProperties = Objects.requireNonNull(workerProperties, "workerProperties must not be null");
        this.maintenanceProperties = Objects.requireNonNull(
                maintenanceProperties,
                "maintenanceProperties must not be null"
        );
    }

    /**
     * Mapping 보정, active registry 복구와 terminal cleanup을 순서대로 한 번 실행한다.
     * Active registry를 먼저 보완해 누락된 회차의 terminal 결과도 같은 tick에서 찾게 한다.
     */
    public ReservationQueueMaintenanceResult runOnce(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        ReservationQueueMappingRepairResult mappings = maintenanceStore.reconcileStaleMappings(
                now.minus(maintenanceProperties.enqueuingTimeout()),
                now,
                maintenanceProperties.batchSize()
        );
        int activeRepaired = maintenanceStore.repairActivePerformances(
                now,
                maintenanceProperties.scanCount()
        );
        int remaining = maintenanceProperties.batchSize();
        int cleaned = 0;
        Instant completedBefore = now.minus(queueProperties.idempotencyRetention());
        for (Long performanceTimeId : expiryIndex.activePerformanceTimeIds()) {
            if (remaining == 0) {
                break;
            }
            ReservationQueueTerminalCleanupResult current = maintenanceStore.cleanupTerminalTickets(
                    performanceTimeId,
                    workerProperties.consumerGroup(),
                    completedBefore,
                    remaining
            );
            cleaned += current.cleaned();
            remaining -= current.inspected();
        }
        expiryIndex.removeStalePerformanceTimes(now);
        return new ReservationQueueMaintenanceResult(
                mappings.repaired(),
                mappings.released(),
                activeRepaired,
                cleaned
        );
    }
}

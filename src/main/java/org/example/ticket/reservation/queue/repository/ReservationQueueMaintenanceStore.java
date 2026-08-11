package org.example.ticket.reservation.queue.repository;

import org.example.ticket.reservation.queue.dto.ReservationQueueMappingRepairResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueTerminalCleanupResult;

import java.time.Instant;

/** Stale mapping, active registry와 terminal retention 정리를 제공한다. */
public interface ReservationQueueMaintenanceStore {

    /**
     * 기준 시각 이전의 ENQUEUING mapping을 ticket 존재 여부에 따라 보정하거나 해제한다.
     * 한 호출에서 처리할 mapping 수는 limit으로 제한한다.
     */
    ReservationQueueMappingRepairResult reconcileStaleMappings(
            Instant staleBefore,
            Instant repairedAt,
            int limit
    );

    /**
     * 제한된 repair candidate 조회로 빠진 active performance score를 복원한다.
     * 반환값은 새로 등록되거나 보존 시각이 연장된 회차 수다.
     */
    int repairActivePerformances(Instant now, int scanCount);

    /**
     * 보존 기준을 지난 terminal ticket 중 pending이 없는 항목을 정리한다.
     * Stream entry와 ticket, 관련 index를 같은 cleanup 명령으로 제거한다.
     */
    ReservationQueueTerminalCleanupResult cleanupTerminalTickets(
            long performanceTimeId,
            String consumerGroup,
            Instant completedBefore,
            int limit
    );
}

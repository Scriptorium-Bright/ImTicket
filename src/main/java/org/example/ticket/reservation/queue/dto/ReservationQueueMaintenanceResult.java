package org.example.ticket.reservation.queue.dto;

/** 한 maintenance tick에서 수행한 복구와 정리 건수를 전달한다. */
public record ReservationQueueMaintenanceResult(
        int mappingsRepaired,
        int mappingsReleased,
        int activePerformancesRepaired,
        int terminalTicketsCleaned
) {

    /**
     * 모든 maintenance 집계가 음수가 아닌지 검증한다.
     * Scheduler 로그와 테스트가 같은 결과 단위를 사용하게 한다.
     */
    public ReservationQueueMaintenanceResult {
        if (mappingsRepaired < 0 || mappingsReleased < 0
                || activePerformancesRepaired < 0 || terminalTicketsCleaned < 0) {
            throw new IllegalArgumentException("Maintenance counts must not be negative");
        }
    }
}

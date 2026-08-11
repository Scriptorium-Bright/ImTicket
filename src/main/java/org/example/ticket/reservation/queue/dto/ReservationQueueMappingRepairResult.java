package org.example.ticket.reservation.queue.dto;

/** Stale ENQUEUING mapping batch의 복구 결과 집계다. */
public record ReservationQueueMappingRepairResult(
        int repaired,
        int released,
        int mismatched
) {

    /**
     * Mapping 보정 결과의 각 집계가 음수가 아닌지 확인한다.
     * Field 불일치는 삭제하지 않고 별도 건수로 보존한다.
     */
    public ReservationQueueMappingRepairResult {
        if (repaired < 0 || released < 0 || mismatched < 0) {
            throw new IllegalArgumentException("Mapping repair counts must not be negative");
        }
    }
}

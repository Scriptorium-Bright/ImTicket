package org.example.ticket.reservation.queue.dto;

/** 한 번의 terminal cleanup에서 검사한 ticket 수와 실제 제거 수를 함께 보존한다. */
public record ReservationQueueTerminalCleanupResult(int inspected, int cleaned) {

    /**
     * 검사 수와 제거 수의 범위를 검증한다.
     * 제거 수가 검사 수를 넘는 잘못된 저장소 결과를 즉시 거절한다.
     */
    public ReservationQueueTerminalCleanupResult {
        if (inspected < 0 || cleaned < 0 || cleaned > inspected) {
            throw new IllegalArgumentException("Terminal cleanup counts are invalid");
        }
    }
}

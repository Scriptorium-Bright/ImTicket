package org.example.ticket.reservation.queue.dto;

/** Redis terminal 전이 시도 결과와 ACK 가능 여부를 구분한다. */
public enum ReservationQueueTerminalResult {
    COMPLETED,
    ALREADY_TERMINAL,
    OWNER_MISMATCH,
    MISSING,
    INVALID_STATE,
    PAYLOAD_MISMATCH,
    UNLINKED;

    /**
     * terminal 상태가 저장돼 Stream ACK을 진행할 수 있는지 확인한다.
     * 신규 완료와 같은 결과의 멱등 재호출에서만 true를 반환한다.
     */
    public boolean allowsAck() {
        return this == COMPLETED || this == ALREADY_TERMINAL;
    }
}

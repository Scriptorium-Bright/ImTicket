package org.example.ticket.reservation.waitingroom.domain;

/** Waiting Room ticket이 가질 수 있는 lifecycle 상태를 정의한다. */
public enum WaitingRoomTicketStatus {
    WAITING,
    ADMITTED,
    COMPLETED,
    CANCELED,
    EXPIRED;

    /** 현재 상태에서 대상 상태로 이동할 수 있는지 확인한다.
     * terminal 상태는 어떤 대상 상태로도 이동하지 않는다. */
    public boolean canTransitionTo(WaitingRoomTicketStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case WAITING -> target == ADMITTED || target == CANCELED || target == EXPIRED;
            case ADMITTED -> target == COMPLETED || target == CANCELED || target == EXPIRED;
            case COMPLETED, CANCELED, EXPIRED -> false;
        };
    }

    /** 현재 상태가 더 이상의 lifecycle 전이를 허용하지 않는지 확인한다.
     * 완료·취소·만료 상태는 영구적인 terminal 상태로 취급한다. */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELED || this == EXPIRED;
    }
}

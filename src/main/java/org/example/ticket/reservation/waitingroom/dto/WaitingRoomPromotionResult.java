package org.example.ticket.reservation.waitingroom.dto;

import java.util.List;

/** 한 promotion 주기에서 ADMITTED·EXPIRED로 전이된 ticket의 최소 정보를 구분해 전달한다. */
public record WaitingRoomPromotionResult(
        List<WaitingRoomTicketTransition> admitted,
        List<WaitingRoomTicketTransition> expired
) {
    /** promotion 결과 list를 방어적 복사로 보관한다.
     * 호출자가 반환 list를 변경해 lifecycle 결과를 오염시키지 못하게 한다. */
    public WaitingRoomPromotionResult {
        admitted = List.copyOf(admitted);
        expired = List.copyOf(expired);
    }
}

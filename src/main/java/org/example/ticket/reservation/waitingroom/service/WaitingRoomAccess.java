package org.example.ticket.reservation.waitingroom.service;

import org.example.ticket.reservation.waitingroom.pass.WaitingRoomPassClaims;

import java.util.Objects;

/** protected zone guard가 검증한 접근 결정과 서명 pass claim을 보관한다. */
public record WaitingRoomAccess(
        WaitingRoomAccessDecision decision,
        WaitingRoomPassClaims claims
) {

    /** BYPASS를 제외한 결정에는 검증된 pass claim이 포함되는지 확인한다.
     * guard 결과가 결정과 claim의 불일치 상태로 전달되지 않게 한다. */
    public WaitingRoomAccess {
        Objects.requireNonNull(decision, "decision must not be null");
        if (decision != WaitingRoomAccessDecision.BYPASS && claims == null) {
            throw new IllegalArgumentException("protected access requires pass claims");
        }
    }
}

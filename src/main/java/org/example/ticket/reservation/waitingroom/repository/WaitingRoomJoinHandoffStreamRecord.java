package org.example.ticket.reservation.waitingroom.repository;

import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffRequest;

/** Redis Stream entry와 원 요청을 함께 표현한다. */
public record WaitingRoomJoinHandoffStreamRecord(
        String streamRecordId,
        WaitingRoomJoinHandoffRequest request
) {
}

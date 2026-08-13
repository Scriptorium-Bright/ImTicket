package org.example.ticket.reservation.waitingroom.dto;

import java.util.UUID;

/** Waiting Room join의 신규 생성 여부와 ticket 순번 결과를 보관한다. */
public record WaitingRoomJoinResult(
        boolean created,
        UUID ticketId,
        long sequence
) {
}

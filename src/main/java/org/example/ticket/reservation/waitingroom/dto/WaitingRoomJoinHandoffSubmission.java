package org.example.ticket.reservation.waitingroom.dto;

import java.util.UUID;

/** join handoff 등록 결과와 중복 요청 여부를 표현한다. */
public record WaitingRoomJoinHandoffSubmission(UUID requestId, UUID ticketId, boolean created) {
    /** 기존 호출자가 ticket ID를 사용하지 않는 경우의 호환 생성자다.
     * 중복 request처럼 ticket ID를 즉시 알 수 없는 결과는 null을 허용한다. */
    public WaitingRoomJoinHandoffSubmission(UUID requestId, boolean created) {
        this(requestId, null, created);
    }

    /** request ID가 존재하는지 검증한다.
     * created 값은 신규 Stream entry 생성 여부를 나타낸다. */
    public WaitingRoomJoinHandoffSubmission {
        if (requestId == null) {
            throw new IllegalArgumentException("requestId must not be null");
        }
    }
}

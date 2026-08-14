package org.example.ticket.reservation.waitingroom.service;

/** Waiting Room protected zone이 요청에 부여하는 접근 결과다. */
public enum WaitingRoomAccessDecision {

    /** Waiting Room이 적용되지 않아 기존 API 흐름을 사용하는 상태다. */
    BYPASS,

    /** 유효한 admitted pass로 새 좌석 조회·예약 side effect를 허용하는 상태다. */
    ACTIVE,

    /** 만료·완료 pass로 기존 최종 idempotency snapshot만 재생할 수 있는 상태다. */
    REPLAY_ONLY
}

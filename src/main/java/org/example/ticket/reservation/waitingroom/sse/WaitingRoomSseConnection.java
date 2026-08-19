package org.example.ticket.reservation.waitingroom.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** 한 browser stream의 owner·emitter·직렬 전송 대기열을 보관한다. */
final class WaitingRoomSseConnection {

    private final UUID connectionId;
    private final WaitingRoomSseTicketKey ticketKey;
    private final long memberId;
    private final SseEmitter emitter;
    private final Deque<WaitingRoomSseEvent> pendingEvents = new ArrayDeque<>();
    private boolean ready;
    private boolean draining;
    private boolean closed;

    /** connection 식별자와 owner·emitter 상태를 초기화한다.
     * pending event queue는 connection별 직렬 전송을 보장한다. */
    WaitingRoomSseConnection(
            UUID connectionId,
            WaitingRoomSseTicketKey ticketKey,
            long memberId,
            SseEmitter emitter
    ) {
        this.connectionId = connectionId;
        this.ticketKey = ticketKey;
        this.memberId = memberId;
        this.emitter = emitter;
    }

    /** connection의 고유 식별자를 반환한다.
     * registry가 completion callback에서 해당 connection을 제거할 때 사용한다. */
    UUID connectionId() {
        return connectionId;
    }

    /** connection이 연결된 performance time과 ticket key를 반환한다.
     * lifecycle event routing의 기준으로 사용한다. */
    WaitingRoomSseTicketKey ticketKey() {
        return ticketKey;
    }

    /** SSE owner의 회원 ID를 반환한다.
     * initial snapshot과 lifecycle snapshot owner 검증에 사용한다. */
    long memberId() {
        return memberId;
    }

    /** Spring MVC async emitter를 반환한다.
     * registry가 event frame을 실제 HTTP stream으로 전송할 때 사용한다. */
    SseEmitter emitter() {
        return emitter;
    }

    /** 직렬 전송 대기 event queue를 반환한다.
     * 호출자는 connection lock 안에서 queue를 조작해야 한다. */
    Deque<WaitingRoomSseEvent> pendingEvents() {
        return pendingEvents;
    }

    /** initial snapshot 전송 준비 여부를 반환한다.
     * 준비 전 lifecycle event는 queue에 들어가지 않는다. */
    boolean ready() {
        return ready;
    }

    /** initial snapshot 전송 준비 상태를 변경한다.
     * registry가 emitter 초기화 완료 시 호출한다. */
    void ready(boolean ready) {
        this.ready = ready;
    }

    /** 전송 drain task 실행 여부를 반환한다.
     * 중복 executor task 등록을 방지하는 상태값이다. */
    boolean draining() {
        return draining;
    }

    /** 전송 drain task 실행 여부를 변경한다.
     * queue가 비거나 새 event가 들어올 때 registry가 갱신한다. */
    void draining(boolean draining) {
        this.draining = draining;
    }

    /** connection 종료 여부를 반환한다.
     * 종료된 emitter에 event를 추가하지 않도록 확인한다. */
    boolean closed() {
        return closed;
    }

    /** connection을 종료 상태로 바꾸고 pending event를 비운다.
     * 이후 lifecycle event 전달을 차단한다. */
    void close() {
        closed = true;
        pendingEvents.clear();
    }
}

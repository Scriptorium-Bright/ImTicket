package org.example.ticket.reservation.waitingroom.constant;

import org.example.ticket.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Waiting Room API와 entry pass 검증 실패를 표현하는 오류 코드다. */
public enum WaitingRoomErrorCode implements ErrorCode {
    WAITING_ROOM_DISABLED(HttpStatus.NOT_FOUND, "WAITING_ROOM_DISABLED", "해당 공연 회차의 입장 대기열이 활성화되지 않았습니다."),
    WAITING_ROOM_TICKET_NOT_FOUND(HttpStatus.NOT_FOUND, "WAITING_ROOM_TICKET_NOT_FOUND", "입장 대기 ticket을 찾을 수 없습니다."),
    WAITING_ROOM_TICKET_NOT_OWNER(HttpStatus.FORBIDDEN, "WAITING_ROOM_TICKET_NOT_OWNER", "본인의 입장 대기 ticket만 조회할 수 있습니다."),
    WAITING_ROOM_TICKET_STATE_CONFLICT(HttpStatus.CONFLICT, "WAITING_ROOM_TICKET_STATE_CONFLICT", "현재 입장 대기 ticket 상태에서 처리할 수 없습니다."),
    WAITING_ROOM_PASS_REQUIRED(HttpStatus.FORBIDDEN, "WAITING_ROOM_PASS_REQUIRED", "입장 대기 pass가 필요합니다."),
    WAITING_ROOM_PASS_INVALID(HttpStatus.FORBIDDEN, "WAITING_ROOM_PASS_INVALID", "입장 대기 pass가 유효하지 않습니다."),
    WAITING_ROOM_PASS_EXPIRED(HttpStatus.FORBIDDEN, "WAITING_ROOM_PASS_EXPIRED", "입장 대기 pass가 만료되었습니다."),
    WAITING_ROOM_REDIS_FAILURE(HttpStatus.SERVICE_UNAVAILABLE, "WAITING_ROOM_REDIS_FAILURE", "입장 대기열을 잠시 처리할 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    /** 오류 코드가 사용할 HTTP 상태, 식별자, 메시지를 저장한다.
     * API 응답과 공통 예외 처리기가 이 값을 함께 사용한다. */
    WaitingRoomErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    /** 오류 코드에 대응하는 HTTP 상태를 반환한다.
     * 클라이언트가 재시도와 인증 실패를 구분할 수 있게 한다. */
    @Override
    public HttpStatus status() {
        return status;
    }

    /** 클라이언트와 로그가 사용할 고정 오류 식별자를 반환한다.
     * 화면 상태와 모니터링 분류의 기준이 되는 문자열이다. */
    @Override
    public String code() {
        return code;
    }

    /** 사용자에게 전달할 기본 오류 메시지를 반환한다.
     * 별도 상세 메시지가 없을 때 공통 응답 body에 사용한다. */
    @Override
    public String message() {
        return message;
    }
}

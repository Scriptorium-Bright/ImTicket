package org.example.ticket.reservation.queue.constant;

import org.example.ticket.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Queue API에서 사용하는 오류와 HTTP 상태를 정의한다. */
public enum ReservationQueueErrorCode implements ErrorCode {
    AUTHENTICATION_REQUIRED(
            HttpStatus.UNAUTHORIZED,
            "QUEUE_AUTHENTICATION_REQUIRED",
            "예약 대기열 인증이 필요합니다."
    ),
    INVALID_REQUEST(
            HttpStatus.BAD_REQUEST,
            "QUEUE_REQUEST_INVALID",
            "예약 대기열 요청 값이 올바르지 않습니다."
    ),
    IDEMPOTENCY_KEY_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "QUEUE_IDEMPOTENCY_KEY_REQUIRED",
            "Idempotency-Key가 필요합니다."
    ),
    IDEMPOTENCY_KEY_INVALID(
            HttpStatus.BAD_REQUEST,
            "QUEUE_IDEMPOTENCY_KEY_INVALID",
            "Idempotency-Key는 canonical UUID여야 합니다."
    ),
    IDEMPOTENCY_CONFLICT(
            HttpStatus.CONFLICT,
            "QUEUE_IDEMPOTENCY_CONFLICT",
            "같은 Idempotency-Key에 다른 예약 요청을 사용할 수 없습니다."
    ),
    ENQUEUE_IN_PROGRESS(
            HttpStatus.SERVICE_UNAVAILABLE,
            "QUEUE_ENQUEUE_IN_PROGRESS",
            "같은 예약 요청의 대기열 접수를 확인하고 있습니다."
    ),
    QUEUE_FULL(
            HttpStatus.TOO_MANY_REQUESTS,
            "RESERVATION_QUEUE_FULL",
            "예약 대기열이 가득 찼습니다. 잠시 후 다시 시도해 주세요."
    ),
    QUEUE_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "RESERVATION_QUEUE_UNAVAILABLE",
            "예약 대기열을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
    ),
    TICKET_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "QUEUE_TICKET_NOT_FOUND",
            "예약 대기열 ticket을 찾을 수 없습니다."
    ),
    TICKET_EXPIRED(
            HttpStatus.GONE,
            "QUEUE_TICKET_EXPIRED",
            "예약 대기열 ticket이 만료되었습니다."
    );

    private final HttpStatus status;
    private final String code;
    private final String message;

    /**
     * Queue 오류의 HTTP 상태, 고정 code와 기본 메시지를 묶는다.
     * 공통 예외 처리기가 일관된 API 오류 응답을 만들 때 사용한다.
     */
    ReservationQueueErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    /**
     * Queue 오류에 대응하는 HTTP 상태를 반환한다.
     * 공통 예외 처리기가 응답 status를 결정할 때 사용한다.
     */
    @Override
    public HttpStatus status() {
        return status;
    }

    /**
     * Queue 오류를 구분하는 고정 식별자를 반환한다.
     * 클라이언트가 재시도와 입력 수정 흐름을 결정할 때 사용한다.
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * Queue 오류의 기본 사용자 메시지를 반환한다.
     * API 응답에 별도 메시지가 없을 때 이 값을 노출한다.
     */
    @Override
    public String message() {
        return message;
    }
}

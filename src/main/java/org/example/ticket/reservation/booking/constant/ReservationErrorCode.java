package org.example.ticket.reservation.booking.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.example.ticket.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReservationErrorCode implements ErrorCode {

    PERFORMANCE_TIME_REQUIRED(HttpStatus.BAD_REQUEST, "PERFORMANCE_TIME_REQUIRED", "공연 회차 식별자는 필수입니다."),
    RESERVATION_SEAT_REQUIRED(HttpStatus.BAD_REQUEST, "RESERVATION_SEAT_REQUIRED", "예약할 좌석은 최소 1개 이상이어야 합니다."),
    DUPLICATE_SEAT_INCLUDED(HttpStatus.CONFLICT, "DUPLICATE_SEAT_INCLUDED", "중복된 좌석이 포함되어 있습니다."),
    INVALID_SEAT_ID(HttpStatus.BAD_REQUEST, "INVALID_SEAT_ID", "좌석 식별자에는 null을 사용할 수 없습니다."),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key가 필요합니다."),
    IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key는 canonical UUID여야 합니다."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "같은 Idempotency-Key에 다른 예약 요청을 사용할 수 없습니다."),
    IDEMPOTENCY_PROCESSING(HttpStatus.CONFLICT, "IDEMPOTENCY_PROCESSING", "같은 예약 요청이 처리 중입니다."),
    IDEMPOTENCY_REPLAY_ONLY(HttpStatus.CONFLICT, "IDEMPOTENCY_REPLAY_ONLY", "만료된 입장 pass에서는 기존 최종 예약 결과만 조회할 수 있습니다."),
    RESERVATION_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION_MEMBER_NOT_FOUND", "예약 회원을 찾을 수 없습니다."),
    RESERVATION_SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION_SEAT_NOT_FOUND", "공연 회차에 속한 좌석을 찾을 수 없습니다."),
    SEAT_ALREADY_RESERVED(HttpStatus.CONFLICT, "SEAT_ALREADY_RESERVED", "이미 예약 완료된 좌석입니다."),
    SEAT_ADMISSION_REJECTED(HttpStatus.TOO_MANY_REQUESTS, "SEAT_ADMISSION_REJECTED", "좌석 예매 요청이 동시에 몰려 즉시 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    SEAT_LOCK_TIMEOUT(HttpStatus.TOO_MANY_REQUESTS, "SEAT_LOCK_TIMEOUT", "요청이 몰려 좌석 선점 대기 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "예약을 찾을 수 없습니다."),
    RESERVATION_NOT_OWNER(HttpStatus.NOT_FOUND, "RESERVATION_NOT_OWNER", "예약을 찾을 수 없습니다."),
    RESERVATION_NOT_PENDING(HttpStatus.BAD_REQUEST, "RESERVATION_NOT_PENDING", "예약 대기 상태가 아닙니다."),
    RESERVATION_EXPIRED(HttpStatus.BAD_REQUEST, "RESERVATION_EXPIRED", "이미 만료된 좌석입니다. 처음부터 다시 진행해야 합니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    /**
     * 예약 오류에 대응하는 HTTP 상태를 반환한다.
     * 공통 예외 처리기가 응답 status를 결정할 때 사용한다.
     */
    @Override
    public HttpStatus status() {
        return status;
    }

    /**
     * 예약 오류를 구분하는 고정 식별자를 반환한다.
     * 클라이언트의 오류 분기와 서버 로그 추적에 사용한다.
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 예약 오류의 기본 사용자 메시지를 반환한다.
     * 공통 응답이 별도 메시지를 지정하지 않을 때 이 값을 사용한다.
     */
    @Override
    public String message() {
        return message;
    }
}

package org.example.ticket.reservation.exception;

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
    SEAT_ALREADY_RESERVED(HttpStatus.CONFLICT, "SEAT_ALREADY_RESERVED", "이미 예약 완료된 좌석입니다."),
    SEAT_LOCK_TIMEOUT(HttpStatus.TOO_MANY_REQUESTS, "SEAT_LOCK_TIMEOUT", "요청이 몰려 좌석 선점 대기 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "예약을 찾을 수 없습니다."),
    RESERVATION_NOT_OWNER(HttpStatus.NOT_FOUND, "RESERVATION_NOT_OWNER", "예약을 찾을 수 없습니다."),
    RESERVATION_NOT_PENDING(HttpStatus.BAD_REQUEST, "RESERVATION_NOT_PENDING", "예약 대기 상태가 아닙니다."),
    RESERVATION_EXPIRED(HttpStatus.BAD_REQUEST, "RESERVATION_EXPIRED", "이미 만료된 좌석입니다. 처음부터 다시 진행해야 합니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}

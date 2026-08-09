package org.example.ticket.reservation.booking.util;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.constant.ReservationFailureType;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 예약 실행 예외를 즉시 재시도, 최종 실패와 lease 보호 대상으로 분류한다. */
@Component
public final class ReservationFailureClassifier {

    private static final Set<ReservationErrorCode> RETRYABLE_ERRORS = Set.of(
            ReservationErrorCode.SEAT_ADMISSION_REJECTED,
            ReservationErrorCode.SEAT_LOCK_TIMEOUT
    );
    private static final Set<ReservationErrorCode> FINAL_ERRORS = Set.of(
            ReservationErrorCode.RESERVATION_MEMBER_NOT_FOUND,
            ReservationErrorCode.RESERVATION_SEAT_NOT_FOUND,
            ReservationErrorCode.SEAT_ALREADY_RESERVED
    );

    /**
     * 공개 도메인 오류와 예외 원인 체인을 검사해 실패 처리 방식을 결정한다.
     * 명시적으로 등록하지 않은 오류는 처리 lease가 소유권을 보호하도록 남긴다.
     */
    public ReservationFailureType classify(RuntimeException exception) {
        if (exception instanceof BusinessException businessException
                && businessException.getErrorCode() instanceof ReservationErrorCode errorCode) {
            if (RETRYABLE_ERRORS.contains(errorCode)) {
                return ReservationFailureType.RETRYABLE;
            }
            if (FINAL_ERRORS.contains(errorCode)) {
                return ReservationFailureType.FINAL;
            }
            return ReservationFailureType.LEASE_GUARDED;
        }

        Throwable current = exception;
        while (current != null) {
            if (current instanceof TransientDataAccessException
                    || current instanceof LockTimeoutException
                    || current instanceof PessimisticLockException) {
                return ReservationFailureType.RETRYABLE;
            }
            current = current.getCause();
        }
        return ReservationFailureType.LEASE_GUARDED;
    }

    /**
     * 최종 실패로 분류된 예외에서 저장 가능한 예약 오류 code를 꺼낸다.
     * 호출 순서가 잘못되거나 공개 오류가 없으면 상태 저장 전에 즉시 실패시킨다.
     */
    public ReservationErrorCode requireFinalErrorCode(RuntimeException exception) {
        if (classify(exception) == ReservationFailureType.FINAL
                && exception instanceof BusinessException businessException
                && businessException.getErrorCode() instanceof ReservationErrorCode errorCode) {
            return errorCode;
        }
        throw new IllegalArgumentException("최종 예약 오류 code가 필요합니다.");
    }
}


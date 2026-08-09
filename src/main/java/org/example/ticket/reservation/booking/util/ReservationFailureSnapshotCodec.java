package org.example.ticket.reservation.booking.util;

import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.exception.ReservationSnapshotException;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** 최종 예약 실패의 공개 오류 code를 versioned DB snapshot으로 변환한다. */
@Component
public final class ReservationFailureSnapshotCodec {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * 최종 실패에 대응하는 공개 오류 식별자를 저장 문자열로 변환한다.
     * 내부 예외 메시지와 stack 정보는 snapshot에 포함하지 않는다.
     */
    public String encode(ReservationErrorCode errorCode) {
        if (errorCode == null) {
            throw new ReservationSnapshotException("최종 예약 오류 code가 없습니다.");
        }
        return errorCode.code();
    }

    /**
     * 저장된 schema와 오류 식별자를 현재 예약 오류 enum으로 복원한다.
     * 해석할 수 없는 값은 재실행 없이 snapshot 손상 예외로 처리한다.
     */
    public ReservationErrorCode decode(Integer schemaVersion, String errorCode) {
        if (schemaVersion == null || schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new ReservationSnapshotException("지원하지 않는 예약 실패 snapshot schema입니다.");
        }
        return Arrays.stream(ReservationErrorCode.values())
                .filter(candidate -> candidate.code().equals(errorCode))
                .findFirst()
                .orElseThrow(() -> new ReservationSnapshotException("알 수 없는 예약 실패 code입니다."));
    }
}


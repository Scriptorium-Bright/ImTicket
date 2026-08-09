package org.example.ticket.reservation.dto;

import org.example.ticket.reservation.model.ReservationIdempotency;
import org.example.ticket.reservation.model.ReservationIdempotencyStatus;

import java.time.LocalDateTime;

/** 예약 생성 멱등성 claim의 현재 상태를 서비스 경계 밖으로 전달하는 읽기 전용 값이다. */
public record ReservationClaimSnapshot(
        Long id,
        Long memberId,
        String requestHash,
        ReservationIdempotencyStatus status,
        String attemptToken,
        LocalDateTime leaseExpiresAt,
        Integer responseSchemaVersion,
        String responsePayload
) {

    /** 영속 엔티티를 서비스 간 전달에 사용할 수 있는 snapshot으로 변환한다. */
    public static ReservationClaimSnapshot from(ReservationIdempotency claim) {
        return new ReservationClaimSnapshot(
                claim.getId(),
                claim.getMember().getId(),
                claim.getRequestHash(),
                claim.getStatus(),
                claim.getAttemptToken(),
                claim.getLeaseExpiresAt(),
                claim.getResponseSchemaVersion(),
                claim.getResponsePayload()
        );
    }
}

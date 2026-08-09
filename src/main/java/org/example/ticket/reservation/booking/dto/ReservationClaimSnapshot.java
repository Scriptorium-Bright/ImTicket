package org.example.ticket.reservation.booking.dto;

import org.example.ticket.reservation.booking.domain.ReservationIdempotency;
import org.example.ticket.reservation.booking.domain.ReservationIdempotencyStatus;

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
        String responsePayload,
        Integer failureSchemaVersion,
        String lastErrorCode
) {

    /**
     * 멱등성 영속 엔티티를 읽기 전용 service snapshot으로 변환한다.
     * transaction 밖의 orchestration이 claim 상태와 replay payload를 판단할 수 있게 한다.
     */
    public static ReservationClaimSnapshot from(ReservationIdempotency claim) {
        return new ReservationClaimSnapshot(
                claim.getId(),
                claim.getMember().getId(),
                claim.getRequestHash(),
                claim.getStatus(),
                claim.getAttemptToken(),
                claim.getLeaseExpiresAt(),
                claim.getResponseSchemaVersion(),
                claim.getResponsePayload(),
                claim.getFailureSchemaVersion(),
                claim.getLastErrorCode()
        );
    }
}

package org.example.ticket.reservation.queue.dto;

import org.example.ticket.reservation.queue.constant.ReservationQueueStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Redis ticket Hash를 application layer에서 사용하는 불변 값으로 변환한 결과다. */
public record ReservationQueueTicketSnapshot(
        UUID ticketId,
        long performanceTimeId,
        String ownerHash,
        UUID ownerToken,
        ReservationQueuePayload payload,
        ReservationQueueStatus status,
        long sequence,
        Long position,
        Instant enqueuedAt,
        Instant deadlineAt,
        ReservationQueueSuccessResult result,
        String errorCode
) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * Redis ticket Hash에서 복원한 식별자, payload와 상태를 검증한다.
     * 저장 데이터 손상을 조회와 Worker 처리 전에 감지한다.
     */
    public ReservationQueueTicketSnapshot {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        if (performanceTimeId <= 0 || sequence <= 0) {
            throw new IllegalArgumentException("Ticket identifiers must be positive");
        }
        if (ownerHash == null || !SHA_256.matcher(ownerHash).matches()) {
            throw new IllegalArgumentException("ownerHash must be a lowercase SHA-256 value");
        }
        Objects.requireNonNull(ownerToken, "ownerToken must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt must not be null");
        Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        if (position != null && position <= 0) {
            throw new IllegalArgumentException("position must be positive");
        }
        if (status == ReservationQueueStatus.SUCCEEDED && result == null) {
            throw new IllegalArgumentException("SUCCEEDED ticket must have result");
        }
        if (status == ReservationQueueStatus.FAILED_FINAL
                && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("FAILED_FINAL ticket must have errorCode");
        }
        if (status != ReservationQueueStatus.SUCCEEDED && result != null) {
            throw new IllegalArgumentException("Only SUCCEEDED ticket can have result");
        }
        if (status != ReservationQueueStatus.FAILED_FINAL && errorCode != null) {
            throw new IllegalArgumentException("Only FAILED_FINAL ticket can have errorCode");
        }
    }

    /**
     * terminal 결과가 없는 기존 대기와 처리 snapshot을 만드는 호환 생성자다.
     * Admission, 만료와 기존 테스트가 result field를 반복해서 전달하지 않게 한다.
     */
    public ReservationQueueTicketSnapshot(
            UUID ticketId,
            long performanceTimeId,
            String ownerHash,
            UUID ownerToken,
            ReservationQueuePayload payload,
            ReservationQueueStatus status,
            long sequence,
            Long position,
            Instant enqueuedAt,
            Instant deadlineAt
    ) {
        this(
                ticketId,
                performanceTimeId,
                ownerHash,
                ownerToken,
                payload,
                status,
                sequence,
                position,
                enqueuedAt,
                deadlineAt,
                null,
                null
        );
    }

    /**
     * 현재 상태의 ticket이 기준 시각에 만료 가능한지 확인한다.
     * WAITING과 RETRY_WAIT 상태만 deadline 만료 대상으로 판단한다.
     */
    public boolean isDueAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        boolean expirable = status == ReservationQueueStatus.WAITING
                || status == ReservationQueueStatus.RETRY_WAIT;
        return expirable && !deadlineAt.isAfter(now);
    }
}

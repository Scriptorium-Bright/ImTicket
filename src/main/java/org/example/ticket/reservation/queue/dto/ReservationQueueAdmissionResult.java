package org.example.ticket.reservation.queue.dto;

import java.util.Objects;
import java.util.UUID;

/** Queue admission 결과를 HTTP 표현과 분리한 내부 값이다. */
public record ReservationQueueAdmissionResult(
        Outcome outcome,
        UUID ticketId,
        long performanceTimeId,
        Long sequence,
        String streamId
) {

    public enum Outcome {
        ACCEPTED,
        EXISTING,
        ENQUEUE_IN_PROGRESS,
        QUEUE_FULL,
        IDEMPOTENCY_CONFLICT
    }

    /**
     * admission outcome별 필수 ticket, sequence와 Stream ID를 검증한다.
     * 서비스가 결과 유형에 맞는 필드만 사용하도록 계약을 고정한다.
     */
    public ReservationQueueAdmissionResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (performanceTimeId <= 0) {
            throw new IllegalArgumentException("performanceTimeId must be positive");
        }
        if (outcome == Outcome.ACCEPTED) {
            Objects.requireNonNull(ticketId, "accepted ticketId must not be null");
            if (sequence == null || sequence <= 0) {
                throw new IllegalArgumentException("accepted sequence must be positive");
            }
            if (streamId == null || streamId.isBlank()) {
                throw new IllegalArgumentException("accepted streamId must not be blank");
            }
        }
        if ((outcome == Outcome.EXISTING || outcome == Outcome.ENQUEUE_IN_PROGRESS) && ticketId == null) {
            throw new IllegalArgumentException("existing ticketId must not be null");
        }
    }

    /**
     * 새 ticket이 Stream에 접수된 성공 결과를 만든다.
     * 생성된 ticket ID, 순번과 Stream entry ID를 모두 보존한다.
     */
    public static ReservationQueueAdmissionResult accepted(
            UUID ticketId,
            long performanceTimeId,
            long sequence,
            String streamId
    ) {
        return new ReservationQueueAdmissionResult(
                Outcome.ACCEPTED,
                ticketId,
                performanceTimeId,
                sequence,
                streamId
        );
    }

    /**
     * 기존 ticket 또는 접수 확인 중인 결과를 만든다.
     * 같은 멱등 요청이 기존 ticket ID로 수렴하게 한다.
     */
    public static ReservationQueueAdmissionResult existing(
            Outcome outcome,
            UUID ticketId,
            long performanceTimeId
    ) {
        if (outcome != Outcome.EXISTING && outcome != Outcome.ENQUEUE_IN_PROGRESS) {
            throw new IllegalArgumentException("outcome must describe an existing admission");
        }
        return new ReservationQueueAdmissionResult(outcome, ticketId, performanceTimeId, null, null);
    }

    /**
     * Queue full 또는 요청 내용 충돌에 대한 거절 결과를 만든다.
     * ticket을 만들지 않은 결과에는 회차와 거절 유형만 남긴다.
     */
    public static ReservationQueueAdmissionResult rejected(Outcome outcome, long performanceTimeId) {
        if (outcome != Outcome.QUEUE_FULL && outcome != Outcome.IDEMPOTENCY_CONFLICT) {
            throw new IllegalArgumentException("outcome must describe a rejected admission");
        }
        return new ReservationQueueAdmissionResult(outcome, null, performanceTimeId, null, null);
    }
}

package org.example.ticket.reservation.queue.application;

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

    public static ReservationQueueAdmissionResult rejected(Outcome outcome, long performanceTimeId) {
        if (outcome != Outcome.QUEUE_FULL && outcome != Outcome.IDEMPOTENCY_CONFLICT) {
            throw new IllegalArgumentException("outcome must describe a rejected admission");
        }
        return new ReservationQueueAdmissionResult(outcome, null, performanceTimeId, null, null);
    }
}

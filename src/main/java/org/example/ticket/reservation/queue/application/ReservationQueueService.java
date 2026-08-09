package org.example.ticket.reservation.queue.application;

import org.example.ticket.reservation.queue.application.port.ReservationQueueAdmissionStore;
import org.example.ticket.reservation.queue.application.port.ReservationQueueStorageException;
import org.example.ticket.reservation.queue.application.port.ReservationQueueTicketStore;
import org.example.ticket.reservation.queue.domain.ReservationQueueRequestFingerprint;
import org.example.ticket.reservation.queue.domain.ReservationQueueStatus;

import org.example.ticket.common.exception.BusinessException;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Queue API 입력을 admission과 ticket store 호출로 연결한다. */
public final class ReservationQueueService {

    private static final String STATUS_PATH = "/api/reservation/pre-reserve/queue/";

    private final ReservationQueueAdmissionStore admissionStore;
    private final ReservationQueueTicketStore ticketStore;
    private final ReservationQueueIdentityHasher identityHasher;
    private final ReservationQueueProperties properties;
    private final Clock clock;
    private final Supplier<UUID> ticketIdSupplier;

    public ReservationQueueService(
            ReservationQueueAdmissionStore admissionStore,
            ReservationQueueTicketStore ticketStore,
            ReservationQueueIdentityHasher identityHasher,
            ReservationQueueProperties properties,
            Clock clock
    ) {
        this(admissionStore, ticketStore, identityHasher, properties, clock, UUID::randomUUID);
    }

    ReservationQueueService(
            ReservationQueueAdmissionStore admissionStore,
            ReservationQueueTicketStore ticketStore,
            ReservationQueueIdentityHasher identityHasher,
            ReservationQueueProperties properties,
            Clock clock,
            Supplier<UUID> ticketIdSupplier
    ) {
        this.admissionStore = Objects.requireNonNull(admissionStore, "admissionStore must not be null");
        this.ticketStore = Objects.requireNonNull(ticketStore, "ticketStore must not be null");
        this.identityHasher = Objects.requireNonNull(identityHasher, "identityHasher must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.ticketIdSupplier = Objects.requireNonNull(ticketIdSupplier, "ticketIdSupplier must not be null");
    }

    public ReservationQueueEnqueueResponse enqueue(
            String walletAddress,
            String idempotencyKey,
            ReservationQueueApiRequest request
    ) {
        String ownerHash = ownerHash(walletAddress);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw error(ReservationQueueErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        String idempotencyKeyHash;
        try {
            idempotencyKeyHash = identityHasher.idempotencyKeyHash(idempotencyKey);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ReservationQueueErrorCode.IDEMPOTENCY_KEY_INVALID, exception);
        }

        ReservationQueueRequestFingerprint fingerprint = fingerprint(request);
        UUID candidateTicketId = Objects.requireNonNull(
                ticketIdSupplier.get(),
                "ticketId must not be null"
        );
        ReservationQueueAdmissionCommand command = new ReservationQueueAdmissionCommand(
                fingerprint.performanceTimeId(),
                candidateTicketId,
                ownerHash,
                idempotencyKeyHash,
                fingerprint.requestHash(),
                fingerprint.normalizedSeatIds(),
                clock.instant()
        );

        ReservationQueueAdmissionResult result;
        try {
            result = admissionStore.admit(command);
        } catch (DataAccessException | ReservationQueueStorageException exception) {
            throw new BusinessException(ReservationQueueErrorCode.QUEUE_UNAVAILABLE, exception);
        }

        return switch (result.outcome()) {
            case ACCEPTED -> enqueueResponse(result, false);
            case EXISTING -> enqueueResponse(result, true);
            case ENQUEUE_IN_PROGRESS -> throw error(ReservationQueueErrorCode.ENQUEUE_IN_PROGRESS);
            case QUEUE_FULL -> throw error(ReservationQueueErrorCode.QUEUE_FULL);
            case IDEMPOTENCY_CONFLICT -> throw error(ReservationQueueErrorCode.IDEMPOTENCY_CONFLICT);
        };
    }

    public ReservationQueueStatusResponse status(
            String walletAddress,
            long performanceTimeId,
            UUID ticketId
    ) {
        String ownerHash = ownerHash(walletAddress);
        if (performanceTimeId <= 0 || ticketId == null) {
            throw error(ReservationQueueErrorCode.INVALID_REQUEST);
        }

        try {
            ReservationQueueTicketSnapshot snapshot = ticketStore.find(performanceTimeId, ticketId)
                    .orElseThrow(() -> error(ReservationQueueErrorCode.TICKET_NOT_FOUND));
            if (!snapshot.ownerHash().equals(ownerHash)) {
                throw error(ReservationQueueErrorCode.TICKET_NOT_FOUND);
            }

            Instant now = clock.instant();
            if (snapshot.isDueAt(now)) {
                ticketStore.expireIfDue(performanceTimeId, ticketId, now);
                snapshot = ticketStore.find(performanceTimeId, ticketId)
                        .orElseThrow(() -> error(ReservationQueueErrorCode.TICKET_NOT_FOUND));
            }
            if (snapshot.status() == ReservationQueueStatus.EXPIRED) {
                throw error(ReservationQueueErrorCode.TICKET_EXPIRED);
            }

            return new ReservationQueueStatusResponse(
                    snapshot.performanceTimeId(),
                    snapshot.ticketId(),
                    snapshot.status().visibleStatus(),
                    snapshot.position(),
                    snapshot.enqueuedAt(),
                    snapshot.deadlineAt(),
                    properties.pollInterval().toMillis()
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (DataAccessException | ReservationQueueStorageException exception) {
            throw new BusinessException(ReservationQueueErrorCode.QUEUE_UNAVAILABLE, exception);
        }
    }

    private ReservationQueueEnqueueResponse enqueueResponse(
            ReservationQueueAdmissionResult result,
            boolean replayed
    ) {
        return new ReservationQueueEnqueueResponse(
                result.performanceTimeId(),
                result.ticketId(),
                ReservationQueueStatus.WAITING,
                replayed,
                properties.pollInterval().toMillis(),
                STATUS_PATH + result.performanceTimeId() + "/" + result.ticketId()
        );
    }

    private String ownerHash(String walletAddress) {
        try {
            return identityHasher.ownerHash(walletAddress);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ReservationQueueErrorCode.AUTHENTICATION_REQUIRED, exception);
        }
    }

    private ReservationQueueRequestFingerprint fingerprint(ReservationQueueApiRequest request) {
        if (request == null || request.performanceTimeId() == null) {
            throw error(ReservationQueueErrorCode.INVALID_REQUEST);
        }
        try {
            return ReservationQueueRequestFingerprint.of(request.performanceTimeId(), request.seatIds());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ReservationQueueErrorCode.INVALID_REQUEST, exception);
        }
    }

    private BusinessException error(ReservationQueueErrorCode errorCode) {
        return new BusinessException(errorCode);
    }
}

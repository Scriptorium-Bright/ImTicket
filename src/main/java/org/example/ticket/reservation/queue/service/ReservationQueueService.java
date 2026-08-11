package org.example.ticket.reservation.queue.service;

import org.example.ticket.reservation.queue.repository.ReservationQueueAdmissionStore;
import org.example.ticket.reservation.queue.exception.ReservationQueueStorageException;
import org.example.ticket.reservation.queue.repository.ReservationQueueTicketStore;
import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.constant.ReservationQueueErrorCode;
import org.example.ticket.reservation.queue.constant.ReservationQueueStatus;
import org.example.ticket.reservation.queue.dto.ReservationQueueAdmissionCommand;
import org.example.ticket.reservation.queue.dto.ReservationQueueAdmissionResult;
import org.example.ticket.reservation.queue.dto.ReservationQueuePayload;
import org.example.ticket.reservation.queue.dto.ReservationQueueTicketSnapshot;
import org.example.ticket.reservation.queue.dto.request.ReservationQueueApiRequest;
import org.example.ticket.reservation.queue.dto.response.ReservationQueueEnqueueResponse;
import org.example.ticket.reservation.queue.dto.response.ReservationQueueStatusResponse;
import org.example.ticket.reservation.queue.util.ReservationQueueIdentityHasher;
import org.example.ticket.reservation.common.factory.ReservationIntentFingerprintFactory;
import org.example.ticket.reservation.common.value.ReservationIdempotencyKey;
import org.example.ticket.reservation.common.value.ReservationIntentFingerprint;

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

    /**
     * 운영 Queue 서비스에 Redis 저장소, 정규화기와 Clock을 주입한다.
     * 새 ticket ID는 호출마다 UUID로 생성한다.
     */
    public ReservationQueueService(
            ReservationQueueAdmissionStore admissionStore,
            ReservationQueueTicketStore ticketStore,
            ReservationQueueIdentityHasher identityHasher,
            ReservationQueueProperties properties,
            Clock clock
    ) {
        this(admissionStore, ticketStore, identityHasher, properties, clock, UUID::randomUUID);
    }

    /**
     * 테스트가 결정적인 ticket ID 공급자를 주입할 수 있게 구성한다.
     * 모든 의존성의 null 검증을 이 생성 경계에서 수행한다.
     */
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

    /**
     * 인증 사용자, 멱등 키와 예약 요청을 정규화해 Redis Queue에 접수한다.
     * admission 결과를 신규 ticket, replay 또는 공개 Queue 오류로 변환한다.
     */
    public ReservationQueueEnqueueResponse enqueue(
            long memberId,
            String walletAddress,
            String idempotencyKey,
            ReservationQueueApiRequest request
    ) {
        String ownerHash = ownerHash(walletAddress);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw error(ReservationQueueErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        ReservationIdempotencyKey canonicalIdempotencyKey;
        try {
            canonicalIdempotencyKey = identityHasher.idempotencyKey(idempotencyKey);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ReservationQueueErrorCode.IDEMPOTENCY_KEY_INVALID, exception);
        }

        ReservationIntentFingerprint fingerprint = fingerprint(request);
        UUID candidateTicketId = Objects.requireNonNull(
                ticketIdSupplier.get(),
                "ticketId must not be null"
        );
        ReservationQueuePayload payload;
        try {
            payload = ReservationQueuePayload.current(
                    memberId,
                    canonicalIdempotencyKey,
                    fingerprint.requestHash(),
                    fingerprint.normalizedSeatIds()
            );
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ReservationQueueErrorCode.AUTHENTICATION_REQUIRED, exception);
        }
        ReservationQueueAdmissionCommand command = new ReservationQueueAdmissionCommand(
                fingerprint.performanceTimeId(),
                candidateTicketId,
                ownerHash,
                payload,
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

    /**
     * 소유자와 ticket 식별자로 Queue의 현재 상태를 조회한다.
     * deadline이 지난 ticket은 만료 전이를 확인한 뒤 공개 상태를 반환한다.
     */
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

    /**
     * admission 성공 결과를 클라이언트 polling 응답으로 변환한다.
     * 기존 ticket 반환 여부와 다음 조회 URL을 함께 제공한다.
     */
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

    /**
     * 인증 wallet을 Redis 소유자 비교용 hash로 변환한다.
     * 유효하지 않은 identity 입력은 Queue 인증 오류로 바꾼다.
     */
    private String ownerHash(String walletAddress) {
        try {
            return identityHasher.ownerHash(walletAddress);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ReservationQueueErrorCode.AUTHENTICATION_REQUIRED, exception);
        }
    }

    /**
     * API 요청을 정렬 좌석과 request hash를 가진 Queue fingerprint로 변환한다.
     * 입력 불변식 위반은 공개 요청 오류로 바꾼다.
     */
    private ReservationIntentFingerprint fingerprint(ReservationQueueApiRequest request) {
        if (request == null || request.performanceTimeId() == null) {
            throw error(ReservationQueueErrorCode.INVALID_REQUEST);
        }
        try {
            return ReservationIntentFingerprintFactory.create(request.performanceTimeId(), request.seatIds());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ReservationQueueErrorCode.INVALID_REQUEST, exception);
        }
    }

    /**
     * Queue error code를 공통 비즈니스 예외로 감싼다.
     * 서비스의 모든 명시적 거절이 같은 API 예외 경로를 사용하게 한다.
     */
    private BusinessException error(ReservationQueueErrorCode errorCode) {
        return new BusinessException(errorCode);
    }
}

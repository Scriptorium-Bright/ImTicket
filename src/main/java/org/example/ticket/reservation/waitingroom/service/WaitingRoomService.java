package org.example.ticket.reservation.waitingroom.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.example.ticket.reservation.waitingroom.constant.WaitingRoomErrorCode;
import org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinResult;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomStatusResponse;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomTicketSnapshot;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomTicketTransition;
import org.example.ticket.reservation.waitingroom.exception.WaitingRoomCapacityException;
import org.example.ticket.reservation.waitingroom.exception.WaitingRoomStorageException;
import org.example.ticket.reservation.waitingroom.pass.WaitingRoomPassClaims;
import org.example.ticket.reservation.waitingroom.pass.WaitingRoomPassCodec;
import org.example.ticket.reservation.waitingroom.repository.WaitingRoomStore;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomPromotionResult;
import org.example.ticket.reservation.waitingroom.sse.WaitingRoomTicketLifecycleEvent;
import org.example.ticket.reservation.waitingroom.util.WaitingRoomTimePolicy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;

/** Waiting Room ticket lifecycle과 API 응답·entry pass 발급을 조율한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitingRoomService {

    private final WaitingRoomStore waitingRoomStore;
    private final WaitingRoomProperties properties;
    private final WaitingRoomTimePolicy timePolicy;
    private final WaitingRoomFeaturePolicy featurePolicy;
    private final WaitingRoomPassCodec passCodec;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;

    /** 회원을 회차별 Waiting Room에 등록하고 현재 ticket 상태를 반환한다.
     * 기존 owner mapping이 있으면 새 ticket을 만들지 않고 상태를 복구한다. */
    public WaitingRoomStatusResponse join(long performanceTimeId, long memberId) {
        requirePositive(performanceTimeId, "performanceTimeId");
        requirePositive(memberId, "memberId");
        requireEnabled(performanceTimeId);
        Instant now = timePolicy.now();
        WaitingRoomJoinResult result;
        try {
            result = executeStorage(() -> waitingRoomStore.join(
                    performanceTimeId,
                    memberId,
                    UUID.randomUUID(),
                    now,
                    timePolicy.waitingDeadline(),
                    storageRetention(),
                    properties.getMaxWaitingTickets()
            ));
        } catch (BusinessException exception) {
            recordCounter("join", exception.getErrorCode().code());
            throw exception;
        }
        recordCounter("join", result.created() ? "created" : "existing");
        WaitingRoomTicketSnapshot snapshot = executeStorage(() -> waitingRoomStore.find(
                performanceTimeId,
                result.ticketId()
        )).orElseThrow(() -> business(WaitingRoomErrorCode.WAITING_ROOM_TICKET_NOT_FOUND));
        return toResponse(performanceTimeId, snapshot);
    }

    /** owner ticket의 현재 상태와 대기 순번 또는 entry pass를 반환한다.
     * WAITING에는 1-based position을, ADMITTED에는 pass를 반환한다. */
    public WaitingRoomStatusResponse status(long performanceTimeId, long memberId, UUID ticketId) {
        requirePositive(performanceTimeId, "performanceTimeId");
        requirePositive(memberId, "memberId");
        requireEnabled(performanceTimeId);
        WaitingRoomTicketSnapshot snapshot = findOwnedSnapshot(performanceTimeId, memberId, ticketId);
        recordCounter("status", snapshot.status().name());
        return toResponse(performanceTimeId, snapshot);
    }

    /** owner ticket을 취소하고 index가 정리된 terminal 상태를 반환한다.
     * terminal snapshot은 설정된 보존 기간 동안 상태 조회에 사용한다. */
    public WaitingRoomStatusResponse cancel(long performanceTimeId, long memberId, UUID ticketId) {
        requirePositive(performanceTimeId, "performanceTimeId");
        requirePositive(memberId, "memberId");
        requireEnabled(performanceTimeId);
        findOwnedSnapshot(performanceTimeId, memberId, ticketId);
        WaitingRoomTicketSnapshot snapshot = executeStorage(() -> waitingRoomStore.cancel(
                performanceTimeId,
                memberId,
                ticketId,
                timePolicy.now(),
                storageRetention()
        )).orElseThrow(() -> business(WaitingRoomErrorCode.WAITING_ROOM_TICKET_STATE_CONFLICT));
        publishLifecycle(snapshot, timePolicy.now());
        return toResponse(performanceTimeId, snapshot);
    }

    /** hold 성공 뒤 owner의 ADMITTED ticket을 완료 상태로 전이한다.
     * active session slot은 Redis transition 안에서 반환된다. */
    public WaitingRoomStatusResponse complete(long performanceTimeId, long memberId, UUID ticketId) {
        requirePositive(performanceTimeId, "performanceTimeId");
        requirePositive(memberId, "memberId");
        requireEnabled(performanceTimeId);
        findOwnedSnapshot(performanceTimeId, memberId, ticketId);
        WaitingRoomTicketSnapshot snapshot = executeStorage(() -> waitingRoomStore.complete(
                performanceTimeId,
                memberId,
                ticketId,
                timePolicy.now(),
                storageRetention()
        )).orElseThrow(() -> business(WaitingRoomErrorCode.WAITING_ROOM_TICKET_STATE_CONFLICT));
        publishLifecycle(snapshot, timePolicy.now());
        return toResponse(performanceTimeId, snapshot);
    }

    /** 한 회차의 만료 ticket 정리와 promotion batch를 실행한다.
     * scheduler와 수동 운영 작업이 같은 Redis admission 경계를 사용한다. */
    public void promote(long performanceTimeId) {
        requirePositive(performanceTimeId, "performanceTimeId");
        if (!featurePolicy.requiresWaitingRoom(performanceTimeId)) {
            return;
        }
        Instant now = timePolicy.now();
        WaitingRoomPromotionResult result = executeStorage(() -> waitingRoomStore.promote(
                performanceTimeId,
                now,
                properties.getEntryLease(),
                properties.getMaxActiveSessions(),
                properties.getAdmitPerInterval(),
                properties.getPromotionInterval(),
                storageRetention()
        ));
        result.expired().forEach(transition -> publishLifecycle(performanceTimeId, transition, now));
        result.admitted().forEach(transition -> publishLifecycle(performanceTimeId, transition, now));
        meterRegistry.counter("imticket.waiting-room.promotions").increment(result.admitted().size());
    }

    /** feature policy에 따라 해당 회차의 Waiting Room 접근을 요구하는지 확인한다.
     * protected zone guard가 API 접근을 결정할 때 호출한다. */
    public boolean requiresWaitingRoom(long performanceTimeId) {
        return featurePolicy.requiresWaitingRoom(performanceTimeId);
    }

    /** join handoff가 기존 join과 같은 feature 검증을 사용하도록 enabled 조건을 노출한다.
     * disabled 회차는 Redis enqueue 전에 domain 오류로 거절한다. */
    public void requireEnabledForJoin(long performanceTimeId) {
        requireEnabled(performanceTimeId);
    }

    /** owner mapping에 등록된 ticket ID를 조회한다.
     * Redis storage 예외는 기존 Waiting Room 오류 contract로 변환한다. */
    public Optional<UUID> findTicketIdByOwner(long performanceTimeId, long memberId) {
        requirePositive(performanceTimeId, "performanceTimeId");
        requirePositive(memberId, "memberId");
        requireEnabled(performanceTimeId);
        return executeStorage(() -> waitingRoomStore.findTicketIdByOwner(performanceTimeId, memberId));
    }

    /** owner가 ticket의 회원과 일치하는지 확인하고 snapshot을 반환한다.
     * 소유자가 다르면 리소스 존재 여부를 노출하지 않는 오류를 반환한다. */
    private WaitingRoomTicketSnapshot findOwnedSnapshot(long performanceTimeId, long memberId, UUID ticketId) {
        if (ticketId == null) {
            throw business(WaitingRoomErrorCode.WAITING_ROOM_TICKET_NOT_FOUND);
        }
        WaitingRoomTicketSnapshot snapshot = executeStorage(() -> waitingRoomStore.find(
                performanceTimeId,
                ticketId
        )).orElseThrow(() -> business(WaitingRoomErrorCode.WAITING_ROOM_TICKET_NOT_FOUND));
        if (snapshot.memberId() != memberId) {
            throw business(WaitingRoomErrorCode.WAITING_ROOM_TICKET_NOT_OWNER);
        }
        return snapshot;
    }

    /** snapshot을 순번·polling·entry pass 응답으로 변환한다.
     * WAITING 순번은 Redis rank에 1을 더해 사용자 기준으로 만든다. */
    private WaitingRoomStatusResponse toResponse(long performanceTimeId, WaitingRoomTicketSnapshot snapshot) {
        OptionalLong rank = executeStorage(() -> waitingRoomStore.waitingRank(performanceTimeId, snapshot.ticketId()));
        Long position = rank.isPresent() ? rank.getAsLong() + 1 : null;
        String entryPass = issuePass(snapshot);
        return new WaitingRoomStatusResponse(
                snapshot.ticketId(),
                snapshot.status(),
                position,
                snapshot.sequence(),
                snapshot.waitingDeadline(),
                snapshot.entryExpiresAt(),
                entryPass,
                timePolicy.statusPollAfter(position).toMillis()
        );
    }

    /** ADMITTED snapshot에만 짧은 수명의 서명 pass를 발급한다.
     * WAITING과 terminal 응답에는 pass를 포함하지 않는다. */
    private String issuePass(WaitingRoomTicketSnapshot snapshot) {
        if (snapshot.status() != WaitingRoomTicketStatus.ADMITTED || snapshot.entryExpiresAt() == null) {
            return null;
        }
        Instant issuedAt = timePolicy.now();
        return passCodec.issue(new WaitingRoomPassClaims(
                snapshot.ticketId(),
                snapshot.memberId(),
                snapshot.performanceTimeId(),
                issuedAt,
                snapshot.entryExpiresAt()
        ));
    }

    /** Redis lifecycle 전이를 local·remote stream notification으로 전달한다.
     * Pub/Sub publisher가 다른 application instance에 상태 변경을 전파한다. */
    private void publishLifecycle(WaitingRoomTicketSnapshot snapshot, Instant occurredAt) {
        eventPublisher.publishEvent(new WaitingRoomTicketLifecycleEvent(
                snapshot.performanceTimeId(),
                snapshot.ticketId(),
                snapshot.status(),
                occurredAt
        ));
    }

    /** promotion 결과의 최소 전이 정보로 lifecycle event를 생성한다.
     * scheduler가 API 응답용 ticket Hash를 다시 읽지 않게 한다. */
    private void publishLifecycle(
            long performanceTimeId,
            WaitingRoomTicketTransition transition,
            Instant occurredAt
    ) {
        eventPublisher.publishEvent(new WaitingRoomTicketLifecycleEvent(
                performanceTimeId,
                transition.ticketId(),
                transition.status(),
                occurredAt
        ));
    }

    /** feature policy가 비활성화한 회차의 요청을 domain 오류로 거절한다.
     * feature flag가 꺼진 회차의 API 진입을 lifecycle 처리 전에 차단한다. */
    private void requireEnabled(long performanceTimeId) {
        if (!featurePolicy.requiresWaitingRoom(performanceTimeId)) {
            throw business(WaitingRoomErrorCode.WAITING_ROOM_DISABLED);
        }
    }

    /** live ticket과 terminal snapshot을 함께 보존할 Redis TTL을 계산한다.
     * waiting TTL과 entry lease 중 긴 값을 terminal retention과 합산한다. */
    private Duration storageRetention() {
        Duration liveWindow = properties.getWaitingTicketTtl().compareTo(properties.getEntryLease()) >= 0
                ? properties.getWaitingTicketTtl()
                : properties.getEntryLease();
        return liveWindow.plus(properties.getTerminalRetention());
    }

    /** storage 호출에서 Redis 장애를 API domain 오류로 변환한다.
     * 클라이언트에는 Waiting Room 전용 503 오류를 전달한다. */
    private <T> T executeStorage(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (WaitingRoomCapacityException exception) {
            recordCounter("storage", "queue_full");
            throw business(WaitingRoomErrorCode.WAITING_ROOM_QUEUE_FULL);
        } catch (WaitingRoomStorageException exception) {
            recordCounter("storage", "redis_failure");
            log.warn("Waiting Room Redis storage failure", exception);
            throw new BusinessException(WaitingRoomErrorCode.WAITING_ROOM_REDIS_FAILURE, exception);
        }
    }

    /** lifecycle 결과를 low-cardinality counter로 기록한다.
     * performanceTimeId와 memberId는 metric tag로 사용하지 않아 cardinality 증가를 막는다. */
    private void recordCounter(String operation, String result) {
        meterRegistry.counter(
                "imticket.waiting-room.operations",
                "operation", operation,
                "result", result
        ).increment();
    }

    /** 양수 식별자만 service lifecycle에 진입하도록 검증한다.
     * 잘못된 회차와 회원 값이 Redis key로 사용되는 것을 차단한다. */
    private void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /** Waiting Room 오류를 호출 지점에서 간결하게 생성한다.
     * 공통 BusinessException 응답 contract를 유지한다. */
    private BusinessException business(WaitingRoomErrorCode errorCode) {
        return new BusinessException(errorCode);
    }
}

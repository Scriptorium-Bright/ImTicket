package org.example.ticket.reservation.waitingroom.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.example.ticket.reservation.waitingroom.constant.WaitingRoomErrorCode;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffRequest;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffResponse;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffState;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffSubmission;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomStatusResponse;
import org.example.ticket.reservation.waitingroom.exception.WaitingRoomCapacityException;
import org.example.ticket.reservation.waitingroom.exception.WaitingRoomStorageException;
import org.example.ticket.reservation.waitingroom.repository.WaitingRoomJoinHandoffStore;
import org.example.ticket.reservation.waitingroom.util.WaitingRoomTimePolicy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** join HTTP 수락과 Redis Stream request state 조회를 담당한다. */
@Service
@RequiredArgsConstructor
public class WaitingRoomJoinHandoffService {

    private final WaitingRoomService waitingRoomService;
    private final WaitingRoomJoinHandoffStore handoffStore;
    private final WaitingRoomProperties properties;
    private final WaitingRoomTimePolicy timePolicy;
    private final MeterRegistry meterRegistry;

    /** feature flag 상태를 반환한다.
     * controller가 동기·비동기 join contract를 선택할 때 사용한다. */
    public boolean enabled() {
        return properties.isAsyncJoinEnabled();
    }

    /** owner의 기존 ticket이 있으면 현재 상태를 반환한다.
     * 기존 ticket이 없으면 handoff enqueue 단계로 진행한다. */
    public Optional<WaitingRoomStatusResponse> findExistingTicket(long performanceTimeId, long memberId) {
        Optional<UUID> ticketId = waitingRoomService.findTicketIdByOwner(performanceTimeId, memberId);
        return ticketId.map(id -> waitingRoomService.status(performanceTimeId, memberId, id));
    }

    /** 중복 요청을 owner mapping으로 수렴시키고 신규 요청만 Stream에 기록한다.
     * Redis 오류와 queue capacity를 HTTP 오류 code로 변환한다. */
    public WaitingRoomJoinHandoffSubmission submit(long performanceTimeId, long memberId) {
        requirePositive(performanceTimeId, "performanceTimeId");
        requirePositive(memberId, "memberId");
        waitingRoomService.requireEnabledForJoin(performanceTimeId);
        var acceptedAt = timePolicy.now();
        WaitingRoomJoinHandoffRequest request = new WaitingRoomJoinHandoffRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                performanceTimeId,
                memberId,
                acceptedAt,
                timePolicy.waitingDeadline(acceptedAt)
        );
        try {
            WaitingRoomJoinHandoffSubmission submission = handoffStore.enqueue(
                    request,
                    storageRetention(),
                    properties.getJoinHandoffQueueCapacity()
            );
            meterRegistry.counter(
                    "imticket.waiting-room.join-handoff.requests",
                    "result", submission.created() ? "created" : "existing"
            ).increment();
            return submission;
        } catch (WaitingRoomCapacityException exception) {
            meterRegistry.counter("imticket.waiting-room.join-handoff.requests", "result", "queue_full").increment();
            throw new BusinessException(WaitingRoomErrorCode.WAITING_ROOM_QUEUE_FULL);
        } catch (WaitingRoomStorageException exception) {
            meterRegistry.counter("imticket.waiting-room.join-handoff.requests", "result", "redis_failure").increment();
            throw new BusinessException(WaitingRoomErrorCode.WAITING_ROOM_REDIS_FAILURE, exception);
        }
    }

    /** request owner를 검증하고 Redis의 authoritative state를 반환한다.
     * SSE 연결과 상태 재조회가 같은 owner 검증을 공유한다. */
    public WaitingRoomJoinHandoffState authorize(long performanceTimeId, long memberId, UUID requestId) {
        WaitingRoomJoinHandoffState state = find(performanceTimeId, requestId);
        if (state.memberId() != memberId) {
            throw new BusinessException(WaitingRoomErrorCode.WAITING_ROOM_TICKET_NOT_OWNER);
        }
        return state;
    }

    /** request 상태를 202 응답 형식으로 변환한다.
     * retryAfterMs를 client 재연결 안내에 포함한다. */
    public WaitingRoomJoinHandoffResponse response(WaitingRoomJoinHandoffState state) {
        return new WaitingRoomJoinHandoffResponse(
                state.requestId(),
                state.status(),
                properties.getJoinHandoffRetryAfter().toMillis(),
                state.ticketId()
        );
    }

    /** 신규 handoff 수락 결과를 202 응답 형식으로 변환한다.
     * 신규 request와 중복 request가 같은 HTTP 응답 구조를 사용한다. */
    public WaitingRoomJoinHandoffResponse queuedResponse(WaitingRoomJoinHandoffSubmission submission) {
        return new WaitingRoomJoinHandoffResponse(
                submission.requestId(),
                WaitingRoomJoinHandoffStatus.QUEUED,
                properties.getJoinHandoffRetryAfter().toMillis(),
                submission.ticketId()
        );
    }

    /** request 보존 기간 안에서 worker state를 조회한다.
     * 만료 request는 ticket not found 오류로 처리한다. */
    public WaitingRoomJoinHandoffState find(long performanceTimeId, UUID requestId) {
        if (requestId == null) {
            throw new BusinessException(WaitingRoomErrorCode.WAITING_ROOM_TICKET_NOT_FOUND);
        }
        return handoffStore.find(performanceTimeId, requestId)
                .orElseThrow(() -> new BusinessException(WaitingRoomErrorCode.WAITING_ROOM_TICKET_NOT_FOUND));
    }

    /** completed request의 ticket status를 owner 검증과 함께 반환한다.
     * frontend가 ticket SSE 연결을 시작할 때 사용할 snapshot을 만든다. */
    public WaitingRoomStatusResponse completedTicket(WaitingRoomJoinHandoffState state) {
        if (state.ticketId() == null || state.status() != WaitingRoomJoinHandoffStatus.COMPLETED) {
            throw new BusinessException(WaitingRoomErrorCode.WAITING_ROOM_TICKET_STATE_CONFLICT);
        }
        return waitingRoomService.status(state.performanceTimeId(), state.memberId(), state.ticketId());
    }

    /** worker가 사용할 ticket state 보존 기간을 계산한다.
     * waiting TTL·entry lease와 terminal retention을 합산한다. */
    public Duration storageRetention() {
        Duration liveWindow = properties.getWaitingTicketTtl().compareTo(properties.getEntryLease()) >= 0
                ? properties.getWaitingTicketTtl()
                : properties.getEntryLease();
        return liveWindow.plus(properties.getTerminalRetention());
    }

    /** handoff key에 사용할 식별자가 양수인지 확인한다.
     * 잘못된 값은 Redis key 구성 전에 차단한다. */
    private void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

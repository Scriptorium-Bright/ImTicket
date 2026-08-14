package org.example.ticket.reservation.booking.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.reservation.booking.dto.request.ReservationRequest;
import org.example.ticket.reservation.booking.dto.response.ReservationCreateResponse;
import org.example.ticket.reservation.booking.util.ReservationRequestHasher;
import org.example.ticket.reservation.booking.util.idempotency.ReservationIntentFingerprint;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomAccess;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomAccessDecision;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomAccessGuard;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomService;
import org.springframework.stereotype.Service;

/** HTTP 예약 입력을 정규화하고 공통 DB claim 실행 경계로 전달한다. */
@Service
@Slf4j
public class ReservationPreReserveService {

    private final ReservationRequestHasher requestHasher;
    private final ReservationClaimExecutionService claimExecutionService;
    private final WaitingRoomAccessGuard accessGuard;
    private final WaitingRoomService waitingRoomService;
    private final MeterRegistry meterRegistry;

    /**
     * HTTP identity 조회와 요청 정규화에 필요한 의존성을 초기화한다.
     * DB claim 상태와 예약 실행 정책은 공통 실행 서비스에 위임한다.
     */
    public ReservationPreReserveService(
            ReservationRequestHasher requestHasher,
            ReservationClaimExecutionService claimExecutionService,
            WaitingRoomAccessGuard accessGuard,
            WaitingRoomService waitingRoomService,
            MeterRegistry meterRegistry
    ) {
        this.requestHasher = requestHasher;
        this.claimExecutionService = claimExecutionService;
        this.accessGuard = accessGuard;
        this.waitingRoomService = waitingRoomService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 외부 멱등 키와 예약 요청을 정규화한 뒤 protected zone access를 먼저 검증한다.
     * 유효한 요청만 새 claim 실행으로 보내고 만료 pass는 최종 snapshot 재생으로 제한한다.
     */
    public ReservationCreateResponse preReserve (
            Long memberId,
            String rawIdempotencyKey,
            String entryPass,
            ReservationRequest request
    ) {
        String idempotencyKey = requestHasher.normalizeKey(rawIdempotencyKey);
        ReservationIntentFingerprint requestFingerprint = requestHasher.fingerprint(request);
        WaitingRoomAccess access = accessGuard.authorize(
                requestFingerprint.performanceTimeId(),
                memberId,
                entryPass
        );

        ReservationCreateResponse response = access.decision() == WaitingRoomAccessDecision.REPLAY_ONLY
                ? claimExecutionService.replayOnly(memberId, idempotencyKey, requestFingerprint)
                : claimExecutionService.execute(memberId, idempotencyKey, request, requestFingerprint);

        if (access.decision() == WaitingRoomAccessDecision.ACTIVE) {
            completeWaitingRoomBestEffort(access);
        }
        return response;
    }

    /** DB hold transaction이 반환된 뒤 Waiting Room active slot 반환을 시도한다.
     * Redis complete 실패는 이미 확정된 예약 응답을 가리지 않고 lease 정리로 회수한다. */
    private void completeWaitingRoomBestEffort(WaitingRoomAccess access) {
        try {
            waitingRoomService.complete(
                    access.claims().performanceTimeId(),
                    access.claims().memberId(),
                    access.claims().ticketId()
            );
        } catch (RuntimeException exception) {
            meterRegistry.counter("imticket.waiting-room.complete.failures").increment();
            log.warn(
                    "Waiting Room complete failed after reservation commit. ticketId={}",
                    access.claims().ticketId(),
                    exception
            );
        }
    }
}

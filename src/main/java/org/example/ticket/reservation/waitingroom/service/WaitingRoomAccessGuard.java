package org.example.ticket.reservation.waitingroom.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.waitingroom.constant.WaitingRoomErrorCode;
import org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomTicketSnapshot;
import org.example.ticket.reservation.waitingroom.exception.WaitingRoomStorageException;
import org.example.ticket.reservation.waitingroom.pass.WaitingRoomPassClaims;
import org.example.ticket.reservation.waitingroom.pass.WaitingRoomPassCodec;
import org.example.ticket.reservation.waitingroom.repository.WaitingRoomStore;
import org.example.ticket.reservation.waitingroom.util.WaitingRoomTimePolicy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/** seat map과 동기 pre-reserve 앞에서 Waiting Room entry pass를 검증한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitingRoomAccessGuard {

    /** 프런트와 protected reservation API가 공유하는 pass header 이름이다. */
    public static final String PASS_HEADER = "X-Waiting-Room-Pass";

    private final WaitingRoomFeaturePolicy featurePolicy;
    private final WaitingRoomStore waitingRoomStore;
    private final WaitingRoomPassCodec passCodec;
    private final WaitingRoomTimePolicy timePolicy;
    private final MeterRegistry meterRegistry;

    /** 회차 정책, JWT member, entry pass와 Redis ticket을 순서대로 검증한다.
     * 적용 회차의 실패는 reservation DB와 idempotency claim에 도달하기 전에 종료한다. */
    public WaitingRoomAccess authorize(long performanceTimeId, Long memberId, String rawPass) {
        requirePositive(performanceTimeId, "performanceTimeId");
        if (!featurePolicy.requiresWaitingRoom(performanceTimeId)) {
            record("BYPASS", "feature_disabled");
            return new WaitingRoomAccess(WaitingRoomAccessDecision.BYPASS, null);
        }
        if (memberId == null || memberId <= 0 || rawPass == null || rawPass.isBlank()) {
            throw reject(WaitingRoomErrorCode.WAITING_ROOM_PASS_REQUIRED, "required");
        }

        WaitingRoomPassClaims claims = parsePass(rawPass);
        if (!claims.belongsTo(memberId, performanceTimeId)) {
            throw reject(WaitingRoomErrorCode.WAITING_ROOM_PASS_INVALID, "owner_or_performance");
        }

        Optional<WaitingRoomTicketSnapshot> snapshot = findSnapshot(performanceTimeId, claims.ticketId());
        if (snapshot.isPresent() && snapshot.get().memberId() != memberId) {
            throw reject(WaitingRoomErrorCode.WAITING_ROOM_PASS_INVALID, "ticket_owner");
        }

        Instant now = timePolicy.now();
        if (claims.isExpired(now)) {
            record("REPLAY_ONLY", "pass_expired");
            return new WaitingRoomAccess(WaitingRoomAccessDecision.REPLAY_ONLY, claims);
        }
        if (snapshot.isEmpty()) {
            throw reject(WaitingRoomErrorCode.WAITING_ROOM_PASS_INVALID, "ticket_missing");
        }

        WaitingRoomTicketSnapshot ticket = snapshot.get();
        if (ticket.status() == WaitingRoomTicketStatus.COMPLETED
                || ticket.status() == WaitingRoomTicketStatus.EXPIRED) {
            record("REPLAY_ONLY", ticket.status().name().toLowerCase());
            return new WaitingRoomAccess(WaitingRoomAccessDecision.REPLAY_ONLY, claims);
        }
        if (ticket.status() == WaitingRoomTicketStatus.ADMITTED
                && ticket.entryExpiresAt() != null
                && ticket.entryExpiresAt().isAfter(now)) {
            record("ACTIVE", "admitted");
            return new WaitingRoomAccess(WaitingRoomAccessDecision.ACTIVE, claims);
        }

        throw reject(WaitingRoomErrorCode.WAITING_ROOM_PASS_INVALID, "ticket_state");
    }

    /** Redis ticket snapshot을 읽고 infrastructure exception을 Waiting Room 오류로 변환한다.
     * pass guard가 raw Redis 오류와 내부 key 정보를 외부 응답에 노출하지 않게 한다. */
    private Optional<WaitingRoomTicketSnapshot> findSnapshot(long performanceTimeId, java.util.UUID ticketId) {
        try {
            return waitingRoomStore.find(performanceTimeId, ticketId);
        } catch (WaitingRoomStorageException exception) {
            record("REJECTED", "redis_failure");
            log.warn("Waiting Room protected zone Redis lookup failed", exception);
            throw new BusinessException(WaitingRoomErrorCode.WAITING_ROOM_REDIS_FAILURE, exception);
        }
    }

    /** 서명 codec의 형식·서명 오류를 고정된 pass invalid 오류로 변환한다.
     * raw token과 parse 상세 메시지는 로그나 API 응답에 기록하지 않는다. */
    private WaitingRoomPassClaims parsePass(String rawPass) {
        try {
            return passCodec.parse(rawPass);
        } catch (IllegalArgumentException exception) {
            throw reject(WaitingRoomErrorCode.WAITING_ROOM_PASS_INVALID, "signature_or_format");
        }
    }

    /** guard 거절 사유를 low-cardinality metric으로 기록하고 domain exception을 생성한다.
     * 오류 code는 클라이언트 contract로 유지하고 reason은 운영 분류에 사용한다. */
    private BusinessException reject(WaitingRoomErrorCode errorCode, String reason) {
        record("REJECTED", reason);
        return new BusinessException(errorCode);
    }

    /** protected zone의 decision과 거절 사유를 metric에 기록한다.
     * performanceTimeId, memberId와 pass 값은 metric tag로 사용하지 않는다. */
    private void record(String decision, String reason) {
        meterRegistry.counter(
                "imticket.waiting-room.guard",
                "decision", decision,
                "reason", reason
        ).increment();
    }

    /** 외부 path 값이 Redis key 계산에 사용되기 전에 양수인지 확인한다.
     * 잘못된 회차 식별자는 공통 400 오류로 처리한다. */
    private void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

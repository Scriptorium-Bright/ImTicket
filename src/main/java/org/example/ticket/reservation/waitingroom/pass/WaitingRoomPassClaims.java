package org.example.ticket.reservation.waitingroom.pass;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** seat map과 동기 pre-reserve 접근을 증명하는 entry pass claim을 표현한다. */
public record WaitingRoomPassClaims(
        UUID ticketId,
        long memberId,
        long performanceTimeId,
        Instant issuedAt,
        Instant expiresAt
) {

    /** pass claim의 식별자와 유효 시간 불변식을 검증한다.
     * issuedAt보다 늦은 expiresAt만 유효한 entry pass로 허용한다. */
    public WaitingRoomPassClaims {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        if (performanceTimeId <= 0) {
            throw new IllegalArgumentException("performanceTimeId must be positive");
        }
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    /** pass가 현재 시각에 만료됐는지 확인한다.
     * expiresAt과 현재 시각이 같으면 만료로 판단한다. */
    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return !expiresAt.isAfter(now);
    }

    /** pass가 지정한 회원과 공연 회차에 발급된 것인지 확인한다.
     * guard가 다른 회원과 회차의 pass 사용을 차단할 때 호출한다. */
    public boolean belongsTo(long expectedMemberId, long expectedPerformanceTimeId) {
        return memberId == expectedMemberId && performanceTimeId == expectedPerformanceTimeId;
    }
}

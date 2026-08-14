package org.example.ticket.reservation.waitingroom.repository;

import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinResult;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomTicketSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Waiting Room lifecycle을 Redis 원자 명령으로 저장하는 저장소 contract다. */
public interface WaitingRoomStore {

    /** 회원·회차의 ticket을 생성하거나 현재 ticket mapping을 반환한다.
     * 동일 owner의 join 요청을 ticket 하나로 수렴시키는 저장소 연산이다. */
    WaitingRoomJoinResult join(
            long performanceTimeId,
            long memberId,
            UUID ticketId,
            Instant enqueuedAt,
            Instant waitingDeadline,
            Duration storageRetention,
            int maxWaitingTickets
    );

    /** ticket Hash를 읽어 현재 lifecycle snapshot을 반환한다.
     * Hash가 사라진 경우 빈 Optional을 반환한다. */
    Optional<WaitingRoomTicketSnapshot> find(long performanceTimeId, UUID ticketId);

    /** WAITING ticket의 zero-based Redis rank를 조회한다.
     * ZRANK가 없는 상태는 admitted 또는 terminal 상태로 해석할 수 있다. */
    OptionalLong waitingRank(long performanceTimeId, UUID ticketId);

    /** 만료 ticket을 정리한 뒤 waiting 앞순번을 admitted로 batch 전이한다.
     * Lua가 active session 상한을 다시 확인해 동시 promotion을 조정한다. */
    List<WaitingRoomTicketSnapshot> promote(
            long performanceTimeId,
            Instant now,
            Duration entryLease,
            int maxActiveSessions,
            int admitPerInterval,
            Duration promotionInterval,
            Duration storageRetention
    );

    /** owner가 가진 WAITING 또는 ADMITTED ticket을 취소 상태로 전이한다.
     * index와 owner mapping도 같은 원자 구간에서 정리한다. */
    Optional<WaitingRoomTicketSnapshot> cancel(
            long performanceTimeId,
            long memberId,
            UUID ticketId,
            Instant now,
            Duration storageRetention
    );

    /** hold 성공 뒤 ADMITTED ticket을 완료 상태로 전이한다.
     * active session slot을 반환하고 terminal snapshot을 보존한다. */
    Optional<WaitingRoomTicketSnapshot> complete(
            long performanceTimeId,
            long memberId,
            UUID ticketId,
            Instant now,
            Duration storageRetention
    );
}

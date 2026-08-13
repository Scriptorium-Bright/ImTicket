package org.example.ticket.reservation.waitingroom.repository.redis;

import java.util.UUID;

/** Waiting Room Redis key 형식과 회차별 hash tag를 한곳에서 관리한다. */
public final class WaitingRoomKeyFactory {

    private static final String PREFIX = "reservation:waiting-room:";

    /** 회차별 단조 증가 sequence를 저장하는 Redis key를 반환한다.
     * sequence 값은 WAITING ticket의 공정한 순서를 계산하는 기반이 된다. */
    public String sequence(long performanceTimeId) {
        return scoped(performanceTimeId, "sequence");
    }

    /** WAITING ticket의 순서를 저장하는 Redis Sorted Set key를 반환한다.
     * member에는 ticket ID를 저장하고 score에는 회차별 sequence를 저장한다. */
    public String waiting(long performanceTimeId) {
        return scoped(performanceTimeId, "waiting");
    }

    /** ADMITTED ticket의 lease 만료 시각을 저장하는 Redis Sorted Set key를 반환한다.
     * score 범위 조회로 만료된 active session을 정리할 수 있다. */
    public String active(long performanceTimeId) {
        return scoped(performanceTimeId, "active");
    }

    /** WAITING ticket의 deadline을 저장하는 Redis Sorted Set key를 반환한다.
     * score에는 waiting deadline epoch millisecond를 저장해 만료 scan에 사용한다. */
    public String deadline(long performanceTimeId) {
        return scoped(performanceTimeId, "deadline");
    }

    /** 개별 ticket의 상태와 claim을 저장하는 Redis Hash key를 반환한다.
     * ticket ID는 회차 hash tag 안에서만 식별자로 사용한다. */
    public String ticket(long performanceTimeId, UUID ticketId) {
        if (ticketId == null) {
            throw new IllegalArgumentException("ticketId must not be null");
        }
        return scoped(performanceTimeId, "ticket:" + ticketId);
    }

    /** 회원·회차별 현재 ticket mapping을 저장하는 Redis String key를 반환한다.
     * 같은 회원의 중복 join을 기존 ticket 하나로 수렴시키는 데 사용한다. */
    public String owner(long performanceTimeId, long memberId) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        return scoped(performanceTimeId, "owner:" + memberId);
    }

    /** 회차 ID와 suffix를 결합해 Redis Cluster에서 같은 hash slot을 사용하게 한다.
     * 여러 key를 사용하는 Lua script가 단일 hash slot에서 실행되도록 보장한다. */
    private String scoped(long performanceTimeId, String suffix) {
        if (performanceTimeId <= 0) {
            throw new IllegalArgumentException("performanceTimeId must be positive");
        }
        return PREFIX + "{" + performanceTimeId + "}:" + suffix;
    }
}

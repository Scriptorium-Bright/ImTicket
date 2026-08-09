package org.example.ticket.reservation.queue.repository.redis;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Queue Redis key를 한곳에서 생성해 hash tag와 식별자 노출 규칙을 유지한다. */
public final class ReservationQueueKeyFactory {

    private static final String PREFIX = "reservation:queue:";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * 공연 회차의 예약 작업 Stream key를 만든다.
     * 같은 회차의 Queue key와 동일한 Redis hash tag를 사용한다.
     */
    public String stream(long performanceTimeId) {
        return scoped(performanceTimeId, "stream");
    }

    /**
     * 처리 중인 전체 ticket 수를 관리하는 admitted ZSET key를 만든다.
     * WAITING, PROCESSING과 RETRY_WAIT 수용량 계산에 사용한다.
     */
    public String admitted(long performanceTimeId) {
        return scoped(performanceTimeId, "admitted");
    }

    /**
     * 현재 대기 순서를 관리하는 waiting ZSET key를 만든다.
     * status 조회가 1부터 시작하는 순번을 계산할 때 사용한다.
     */
    public String waiting(long performanceTimeId) {
        return scoped(performanceTimeId, "waiting");
    }

    /**
     * ticket 만료 시각을 관리하는 deadline ZSET key를 만든다.
     * 만료 scanner가 due ticket을 시간 순서로 찾게 한다.
     */
    public String deadline(long performanceTimeId) {
        return scoped(performanceTimeId, "deadline");
    }

    /**
     * 회차별 단조 증가 순번을 저장하는 key를 만든다.
     * admission Lua가 ticket과 waiting score에 같은 순번을 부여한다.
     */
    public String sequence(long performanceTimeId) {
        return scoped(performanceTimeId, "sequence");
    }

    /**
     * 공연 회차와 UUID로 개별 ticket Hash key를 만든다.
     * 회차 hash tag를 유지해 Lua의 multi-key 실행을 허용한다.
     */
    public String ticket(long performanceTimeId, UUID ticketId) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        return scoped(performanceTimeId, "ticket:" + ticketId);
    }

    /**
     * 소유자 hash와 멱등 키 hash로 접수 mapping key를 만든다.
     * 원문 wallet과 client key를 Redis key에 포함하지 않는다.
     */
    public String idempotency(String ownerHash, String idempotencyKeyHash) {
        requireSha256(ownerHash, "ownerHash");
        requireSha256(idempotencyKeyHash, "idempotencyKeyHash");
        return PREFIX + "idempotency:" + ownerHash + ":" + idempotencyKeyHash;
    }

    /**
     * 만료 scan 대상 공연 회차를 관리하는 전역 ZSET key를 반환한다.
     * 회차별 key 검색 없이 활성 Queue 목록을 찾게 한다.
     */
    public String activePerformanceTimes() {
        return PREFIX + "active-performance-times";
    }

    /**
     * 회차 ID hash tag와 suffix를 결합한 Queue key를 만든다.
     * 양수가 아닌 회차 ID는 key 생성 전에 거절한다.
     */
    private String scoped(long performanceTimeId, String suffix) {
        if (performanceTimeId <= 0) {
            throw new IllegalArgumentException("performanceTimeId must be positive");
        }
        return PREFIX + "{" + performanceTimeId + "}:" + suffix;
    }

    /**
     * Redis key에 들어갈 식별자가 소문자 SHA-256 형식인지 확인한다.
     * 검증 실패 메시지에 대상 필드 이름을 포함한다.
     */
    private void requireSha256(String value, String name) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
    }
}

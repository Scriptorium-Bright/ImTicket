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
     * 처리 lease가 유효한 ticket을 관리하는 processing ZSET key를 만든다.
     * score에는 lease 만료 시각을 저장해 137.6 recovery가 범위 조회할 수 있게 한다.
     */
    public String processing(long performanceTimeId) {
        return scoped(performanceTimeId, "processing");
    }

    /**
     * 재시도 대기 ticket과 due 시각을 관리하는 ZSET key를 만든다.
     * Worker thread가 sleep하지 않고 due 범위 조회로 재처리 대상을 찾게 한다.
     */
    public String retry(long performanceTimeId) {
        return scoped(performanceTimeId, "retry");
    }

    /**
     * 완료, 최종 실패와 만료 ticket의 정리 시각을 관리하는 ZSET key를 만든다.
     * Cleanup이 비terminal ticket을 조회하지 않고 보존 기간이 지난 결과만 찾게 한다.
     */
    public String terminal(long performanceTimeId) {
        return scoped(performanceTimeId, "terminal");
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
     * 확정되지 않은 idempotency mapping과 생성 시각을 관리하는 전역 ZSET key를 반환한다.
     * Maintenance가 전체 mapping key를 scan하지 않고 stale 후보만 찾게 한다.
     */
    public String enqueuingMappings() {
        return PREFIX + "enqueuing-mappings";
    }

    /**
     * Enqueue 뒤 active registry 확정이 필요한 회차와 보존 시각을 관리하는 ZSET key를 반환한다.
     * 정상 갱신은 후보를 제거하고 실패한 갱신만 maintenance 입력으로 남긴다.
     */
    public String activeRepairCandidates() {
        return PREFIX + "active-repair-candidates";
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

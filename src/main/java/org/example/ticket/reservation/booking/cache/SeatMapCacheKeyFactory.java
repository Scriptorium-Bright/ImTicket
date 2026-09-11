package org.example.ticket.reservation.booking.cache;

/** 좌석 snapshot과 version key를 한 곳에서 생성한다. */
public final class SeatMapCacheKeyFactory {

    private static final String KEY_PREFIX = "reservation:seat-map:{%d}";

    /**
     * 공연 회차별 snapshot key를 반환한다.
     * Redis cluster hash tag를 회차 ID에 고정해 관련 key의 규칙을 통일한다.
     */
    public String snapshot(long performanceTimeId) {
        return KEY_PREFIX.formatted(performanceTimeId) + ":snapshot:v2";
    }

    /**
     * migration 기간에 남아 있는 기존 snapshot key를 반환한다.
     * invalidation 시 v2 key와 함께 삭제할 대상이다.
     */
    public String legacySnapshot(long performanceTimeId) {
        return KEY_PREFIX.formatted(performanceTimeId) + ":snapshot";
    }

    /**
     * 회차 invalidation version key를 반환한다.
     * snapshot key와 동일한 Redis cluster hash slot을 사용한다.
     */
    public String version(long performanceTimeId) {
        return KEY_PREFIX.formatted(performanceTimeId) + ":version";
    }
}

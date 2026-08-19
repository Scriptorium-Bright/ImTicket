package org.example.ticket.reservation.booking.cache;

/** 좌석 snapshot cache key를 한 곳에서 생성한다. */
public final class SeatMapCacheKeyFactory {

    private static final String KEY_FORMAT = "reservation:seat-map:{%d}:snapshot";

    /**
     * 공연 회차별 snapshot key를 반환한다.
     * Redis cluster hash tag를 회차 ID에 고정해 관련 key의 규칙을 통일한다.
     */
    public String snapshot(long performanceTimeId) {
        return KEY_FORMAT.formatted(performanceTimeId);
    }
}

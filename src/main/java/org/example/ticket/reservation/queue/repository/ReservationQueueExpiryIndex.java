package org.example.ticket.reservation.queue.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 만료 scanner가 순회할 performance time과 due ticket을 조회한다. */
public interface ReservationQueueExpiryIndex {

    /**
     * 현재 ticket을 가진 공연 회차 ID 목록을 조회한다.
     * 만료 scan이 전체 회차 key를 검색하지 않고 대상만 순회하게 한다.
     */
    List<Long> activePerformanceTimeIds();

    /**
     * 기준 시각까지 만료된 ticket ID를 제한된 개수로 조회한다.
     * 한 번의 scheduler 실행이 처리할 Redis 작업량을 제한한다.
     */
    List<UUID> dueTicketIds(long performanceTimeId, Instant now, int limit);

    /**
     * 보존 시각이 지난 공연 회차를 active index에서 제거한다.
     * ticket이 사라진 회차가 이후 만료 scan에 남지 않게 한다.
     */
    void removeStalePerformanceTimes(Instant now);
}

package org.example.ticket.reservation.booking.cache;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** 좌석 snapshot cache의 조회·version·조건부 저장·무효화 contract다. */
public interface SeatMapCacheStore {

    /**
     * 현재 version과 일치하는 회차 snapshot을 조회한다.
     * snapshot이 없거나 version이 다르면 empty를 반환한다.
     */
    Optional<SeatMapCacheSnapshot> get(long performanceTimeId);

    /**
     * 회차의 현재 invalidation version을 조회한다.
     * 아직 invalidation이 없으면 초기 version 0을 사용한다.
     */
    long currentVersion(long performanceTimeId);

    /**
     * expectedVersion이 현재 version과 같을 때만 snapshot을 저장한다.
     * 반환값 false는 writer 경합으로 저장을 건너뛴 경우다.
     */
    boolean putIfVersionMatches(
            long performanceTimeId,
            long expectedVersion,
            List<SeatMapCacheEntry> entries,
            Duration ttl
    );

    /**
     * version을 증가시키고 회차 snapshot을 원자적으로 삭제한다.
     * 좌석 상태 변경 transaction이 commit된 뒤 호출하는 것을 전제로 한다.
     */
    void evict(long performanceTimeId);
}

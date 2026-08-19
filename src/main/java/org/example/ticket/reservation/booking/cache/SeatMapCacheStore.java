package org.example.ticket.reservation.booking.cache;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** 좌석 snapshot cache의 저장·조회·삭제 contract다. */
public interface SeatMapCacheStore {

    /**
     * 회차 snapshot을 조회하고 없으면 empty를 반환한다.
     * storage 장애는 구현체가 cache 경계 예외로 변환한다.
     */
    Optional<List<SeatMapCacheEntry>> get(long performanceTimeId);

    /**
     * 회차 snapshot을 TTL과 함께 저장한다.
     * 저장 실패는 호출자가 DB 응답을 유지할 수 있도록 명시적인 예외로 전달한다.
     */
    void put(long performanceTimeId, List<SeatMapCacheEntry> entries, Duration ttl);

    /**
     * 회차 snapshot을 삭제한다.
     * 좌석 상태 변경 transaction이 commit된 뒤 호출하는 것을 전제로 한다.
     */
    void evict(long performanceTimeId);
}

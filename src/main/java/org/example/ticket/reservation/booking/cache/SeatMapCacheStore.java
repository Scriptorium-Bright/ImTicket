package org.example.ticket.reservation.booking.cache;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** 좌석 layout·availability 읽기 모델의 Redis 조회·저장·부분 갱신 contract다. */
public interface SeatMapCacheStore {

    /**
     * layout과 availability의 generation·좌석 집합이 일치할 때만 읽기 모델을 반환한다.
     * 구성 요소가 없거나 서로 다른 generation이면 empty를 반환한다.
     */
    Optional<SeatMapCacheParts> get(long performanceTimeId);

    /** layout generation을 읽는다.
     * key가 없으면 초기 세대인 0을 반환한다. */
    long currentLayoutGeneration(long performanceTimeId);

    /** availability generation을 읽는다.
     * key가 없으면 초기 세대인 0을 반환한다. */
    long currentAvailabilityGeneration(long performanceTimeId);

    /** 두 generation이 유지될 때 layout과 availability를 하나의 읽기 모델로 저장한다.
     * 반환값 false는 동시 구조 변경으로 저장을 거부한 경우다. */
    boolean putIfGenerationsMatch(
            long performanceTimeId,
            long expectedLayoutGeneration,
            long expectedAvailabilityGeneration,
            List<SeatLayoutCacheEntry> layoutEntries,
            List<SeatAvailabilityCacheEntry> availabilityEntries,
            Duration ttl
    );

    /** live availability Hash가 있을 때만 version이 최신인 좌석 필드를 부분 갱신한다.
     * 캐시가 없으면 다음 조회가 전체 재구축을 수행하도록 false를 반환한다. */
    boolean updateAvailability(long performanceTimeId, List<SeatAvailabilityCacheEntry> entries);

    /** 구조 변경 generation을 증가시키고 두 읽기 모델을 원자적으로 삭제한다.
     * 상태 변경 transaction이 commit된 뒤 호출하는 것을 전제로 한다. */
    void evict(long performanceTimeId);
}

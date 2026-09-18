package org.example.ticket.reservation.booking.cache;

import lombok.RequiredArgsConstructor;
import org.example.ticket.reservation.booking.dto.response.SeatResponse;
import org.example.ticket.reservation.booking.repository.SeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/** cache miss 또는 cache 비활성 시 MySQL projection을 읽는 경계를 소유한다. */
@Service
@RequiredArgsConstructor
public class SeatMapDatabaseReader {

    private final SeatRepository seatRepository;

    /**
     * cache miss 시 기존 DTO projection을 read-only transaction으로 조회한다.
     * cache hit 경로가 Hikari connection을 획득하지 않도록 별도 bean으로 둔다.
     */
    @Transactional(readOnly = true)
    public List<SeatResponse> read(long performanceTimeId) {
        return seatRepository.findSeatMapByPerformanceTimeId(performanceTimeId);
    }

    /** split cache 재구축에 필요한 정적·동적 projection을 하나의 읽기 transaction에서 조회한다.
     * 두 목록을 같은 시점에 읽어 generation 검증에 사용할 입력을 만든다. */
    @Transactional(readOnly = true)
    public SeatMapDatabaseSnapshot readSplit(long performanceTimeId) {
        return new SeatMapDatabaseSnapshot(
                seatRepository.findSeatLayoutByPerformanceTimeId(performanceTimeId),
                seatRepository.findSeatAvailabilityByPerformanceTimeId(performanceTimeId)
        );
    }

    /** commit 이후 상태 변경 좌석의 확정된 status·JPA version을 새 transaction에서 읽는다.
     * 완료된 쓰기 transaction의 영속성 컨텍스트에 의존하지 않는다. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<SeatAvailabilityCacheEntry> readAvailability(
            long performanceTimeId,
            Collection<Long> seatIds
    ) {
        if (seatIds == null || seatIds.isEmpty()) {
            return List.of();
        }
        return seatRepository.findSeatAvailabilityByPerformanceTimeIdAndIds(
                performanceTimeId,
                seatIds.stream().toList()
        );
    }
}

package org.example.ticket.reservation.booking.cache;

import lombok.RequiredArgsConstructor;
import org.example.ticket.reservation.booking.dto.response.SeatResponse;
import org.example.ticket.reservation.booking.repository.SeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}

package org.example.ticket.reservation.booking.cache;

import lombok.RequiredArgsConstructor;
import org.example.ticket.reservation.booking.domain.Seat;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Objects;

/** 좌석 상태 변경 지점이 cache 구현을 직접 알지 않도록 invalidation event 발행을 감싼다. */
@Component
@RequiredArgsConstructor
public class SeatMapInvalidationPublisher {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 지정 회차의 좌석 변경 invalidation event를 발행한다.
     * 실제 Redis 삭제는 transaction listener가 commit 뒤 수행한다.
     */
    public void publishForPerformanceTime(Long performanceTimeId) {
        if (performanceTimeId != null) {
            eventPublisher.publishEvent(new SeatMapInvalidationEvent(performanceTimeId));
        }
    }

    /**
     * 변경된 좌석의 회차를 중복 제거해 invalidation event를 발행한다.
     * 여러 좌석을 한 transaction에서 변경해도 회차별 event는 한 번만 보낸다.
     */
    public void publishForSeats(Collection<Seat> seats) {
        seats.stream()
                .map(Seat::getPerformanceTime)
                .filter(Objects::nonNull)
                .map(performanceTime -> performanceTime.getId())
                .filter(Objects::nonNull)
                .distinct()
                .forEach(this::publishForPerformanceTime);
    }
}

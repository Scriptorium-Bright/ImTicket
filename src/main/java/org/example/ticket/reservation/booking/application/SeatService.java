package org.example.ticket.reservation.booking.application;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.performance.model.SeatPrice;
import org.example.ticket.performance.repository.PerformanceTimeRepository;
import org.example.ticket.reservation.booking.api.SeatResponse;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.reservation.booking.persistence.SeatRepository;
import org.example.ticket.reservation.booking.concurrency.ReservationLockStrategy;
import org.example.ticket.reservation.booking.concurrency.ReservationLockStrategyContext;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.example.ticket.venue.model.VenueHall;
import org.example.ticket.venue.model.VenueHallSeatTemplate;
import org.example.ticket.venue.repository.VenueHallSeatTemplateRepository;
import org.jetbrains.annotations.NotNull;
import org.springframework.scheduling.annotation.Async;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatService {

    private final SeatRepository repository;
    private final PerformanceTimeRepository performanceTimeRepository;
    private final VenueHallSeatTemplateRepository seatTemplateRepository;
    private final ReservationLockStrategyContext lockStrategyContext;

    @Value("${reservation.lock-strategy:reentrant}")
    private String reservationLockStrategy;

    /**
     * 요청한 공연 회차의 좌석을 조회하고, 비관적 잠금 전략일 때는 DB 행 잠금까지 획득한다.
     * 조회된 수가 요청 수와 다르면 다른 회차의 좌석 또는 존재하지 않는 좌석이 포함된 것으로 처리한다.
     */
    public List<Seat> findAndLockSeatsByPerformanceTime(Long performanceTimeId, List<Long> seatIds) {
        List<Seat> seats = usesDatabasePessimisticLock()
                ? repository.findByPerformanceTimeIdAndIdsForUpdate(performanceTimeId, seatIds)
                : repository.findByPerformanceTimeIdAndIds(performanceTimeId, seatIds);
        if (seats.size() != seatIds.size()) {
            throw new EntityNotFoundException("요청한 공연 회차에 속하지 않는 좌석이 포함되어 있습니다.");
        }
        return seats;
    }

    /** 현재 요청에 적용된 잠금 전략을 우선 사용하고, 없으면 설정값으로 DB 비관적 잠금 사용 여부를 결정한다. */
    private boolean usesDatabasePessimisticLock() {
        ReservationLockStrategy strategy = lockStrategyContext.currentStrategy()
                .orElseGet(() -> ReservationLockStrategy.from(reservationLockStrategy));
        return strategy == ReservationLockStrategy.PESSIMISTIC;
    }

    /** 이미 조회·잠금된 좌석 목록의 예약 상태를 같은 영속성 컨텍스트에서 일괄 변경한다. */
    public void changeSeatsState(List<Seat> seats, SeatStatus seatStatus) {
        seats.forEach(seat -> seat.markAsReserved(seatStatus));
    }

    /** 공연 회차의 좌석 배치와 현재 상태를 변경 없이 조회해 좌석도 응답으로 반환한다. */
    @Transactional(readOnly = true)
    public List<SeatResponse> viewSeatMap(Long performanceTimeId) {
        return repository.findSeatMapByPerformanceTimeId(performanceTimeId);
    }

    /**
     * 공연장 좌석 템플릿과 공연별 가격 정보를 조합해 한 공연 회차의 초기 좌석 데이터를 비동기로 생성한다.
     * 템플릿이나 가격 정보가 불완전하면 완료된 미래값에 예외를 담아 호출자가 실패를 확인할 수 있게 한다.
     */
    @Async("seatCreationTaskExecutor")
    @Transactional
    public CompletableFuture<Void> preprocessSeatData(Long performanceTimeId, String walletAddress) {


        try {
            PerformanceTime performanceTime = performanceTimeRepository.findById(performanceTimeId)
                    .orElseThrow(() -> new EntityNotFoundException("해당 공연장(공연) 시간을 찾을 수 없습니다."));

            VenueHall venueHall = performanceTime.getVenueHall();
            Performance performance = performanceTime.getPerformance();
            if (walletAddress != null && performance.isManagedBy(walletAddress)) {
                throw new EntityNotFoundException("본인 공연만 좌석을 생성할 수 있습니다.");
            }

            Map<SeatInfo, Integer> priceMap = getPriceInfo(performance);

            if (priceMap.isEmpty()) {
                throw new IllegalStateException("Seat price info is empty for performanceTimeId: " + performanceTimeId);
            }

            List<VenueHallSeatTemplate> templates =
                    seatTemplateRepository.findActiveTemplatesByHallId(venueHall.getId());
            if (templates.isEmpty()) {
                throw new IllegalStateException("Seat template is empty for venueHallId: " + venueHall.getId());
            }

            List<Seat> seatsToSave = templates.stream()
                    .map(template -> processSeat(template, performanceTime, priceMap))
                    .toList();
            repository.saveAll(seatsToSave);
        } catch (IllegalStateException e) {
            log.error("Async seat preprocessing failed", e);
            return CompletableFuture.failedFuture(e);
        }

        return CompletableFuture.completedFuture(null);
    }

    /** 공연에 정의된 좌석 등급별 가격을 좌석 생성에 쓰기 쉬운 맵으로 변환한다. */
    @NotNull
    private static Map<SeatInfo, Integer> getPriceInfo(Performance performance) {
        return performance.getSeatPrices()
                .stream()
                .collect(
                        Collectors.toMap(
                                SeatPrice::getSeatInfo,
                                SeatPrice::getPrice
                        )
                );
    }


    /** 좌석 템플릿 하나에 해당 등급의 가격과 기본 AVAILABLE 상태를 적용해 회차 좌석 엔티티를 만든다. */
    private static Seat processSeat(
            VenueHallSeatTemplate seatTemplate,
            PerformanceTime performanceTime,
            Map<SeatInfo, Integer> priceMap
    ) {
        SeatInfo seatInfo = seatTemplate.getSeatInfo();
        Integer price = priceMap.get(seatInfo);
        if (price == null) {
            throw new IllegalStateException("Seat price is missing for seatInfo: " + seatInfo);
        }

        return Seat.builder()
                .performanceTime(performanceTime)
                .seatFloor(seatTemplate.getFloor())
                .seatSection(seatTemplate.getSection())
                .seatRow(seatTemplate.getRow())
                .seatNumber(seatTemplate.getSeatNumber())
                .seatType(seatInfo)
                .price(price)
                .seatStatus(SeatStatus.AVAILABLE)
                .build();
    }

}

package org.example.ticket.reservation.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.performance.model.SeatPrice;
import org.example.ticket.performance.repository.PerformanceTimeRepository;
import org.example.ticket.reservation.response.SeatResponse;
import org.example.ticket.reservation.model.Seat;
import org.example.ticket.reservation.repository.SeatRepository;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.example.ticket.venue.model.VenueHall;
import org.example.ticket.venue.model.VenueHallSeatTemplate;
import org.example.ticket.venue.repository.VenueHallSeatTemplateRepository;
import org.jetbrains.annotations.NotNull;
import org.springframework.scheduling.annotation.Async;
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

    public List<Seat> findAndLockSeatsByPerformanceTime(Long performanceTimeId, List<Long> seatIds) {
        List<Seat> seats = repository.findByPerformanceTimeIdAndIdsForUpdate(performanceTimeId, seatIds);
        if (seats.size() != seatIds.size()) {
            throw new EntityNotFoundException("요청한 공연 회차에 속하지 않는 좌석이 포함되어 있습니다.");
        }
        return seats;
    }

    public void changeSeatsState(List<Seat> seats, SeatStatus seatStatus) {
        seats.forEach(seat -> seat.markAsReserved(seatStatus));
    }

    @Transactional(readOnly = true)
    public List<SeatResponse> viewSeatMap(Long performanceTimeId) {
        return repository.findSeatMapByPerformanceTimeId(performanceTimeId);
    }

    @Async("seatCreationTaskExecutor")
    @Transactional
    public CompletableFuture<Void> preprocessSeatData(Long performanceTimeId, String walletAddress) {


        try {
            PerformanceTime performanceTime = performanceTimeRepository.findById(performanceTimeId)
                    .orElseThrow(() -> new EntityNotFoundException("해당 공연장(공연) 시간을 찾을 수 없습니다."));

            VenueHall venueHall = performanceTime.getVenueHall();
            Performance performance = performanceTime.getPerformance();
            if (walletAddress != null && !performance.isManagedBy(walletAddress)) {
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

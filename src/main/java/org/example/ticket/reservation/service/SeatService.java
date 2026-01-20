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
import org.example.ticket.venue.model.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatService {

    private final SeatRepository repository;
    private final PerformanceTimeRepository performanceTimeRepository;


    @Transactional
    public List<Seat> findAndLockSeatsByIds(List<Long> seatId) {
        return repository.findByIdsForUpdate(seatId);
    }

    @Transactional
    public List<Seat> findAndLockSeatsByIdsWithDistribution(List<Long> seatId) {
        return repository.findByIdsForUpdateWithDistribution(seatId);
    }

    @Transactional
    public List<Seat> findAndLockSeatsByIdsWithOptimistic(List<Long> seatId) {
        return repository.findByIdsForUpdateWithOptimistic(seatId);
    }

    @Transactional
    public void changeSeatsState(List<Seat> seats, SeatStatus seatStatus) {
        seats.forEach(seat -> seat.markAsReserved(seatStatus));
    }

    @Transactional(readOnly = true)
    public List<SeatResponse> viewEmptySeatList(Long performanceTimeId) {
        return repository.findByEmptySeat(performanceTimeId);
    }

    @Async("seatCreationTaskExecutor")
    @Transactional
    public void preprocessSeatData(Long performanceTimeId) {


        PerformanceTime performanceTime = performanceTimeRepository.findById(performanceTimeId)
                .orElseThrow(() -> new EntityNotFoundException("해당 공연장(공연) 시간을 찾을 수 없습니다."));

        VenueHall venueHall = performanceTime.getVenueHall();
        Performance performance = performanceTime.getPerformance();

        Map<SeatInfo, Integer> priceMap = getPriceInfo(performance);

        if(priceMap.isEmpty()) {
            log.debug("Seat Info is Empty");
        }

        List<VenueHallFloor> floorList = venueHall.getFloorList();

        List<Seat> seatsToSave = venueHall.getFloorList().stream()
                .flatMap(floor -> floor.getSections().stream()
                        .flatMap(sections -> sections.getRows().stream()
                                .flatMap(row -> row.getSeats().stream() // Stream<VenueHallSeat>
                                        .map(seatTemplate -> {
                                            SeatInfo seatInfo = seatTemplate.getSeatInfo();
                                            Integer price = priceMap.get(seatInfo);
                                            return processSeat(floor, sections, row, seatTemplate, performanceTime, seatInfo, price);
                                        })))).toList();




        repository.saveAll(seatsToSave);
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


    private static Seat processSeat(VenueHallFloor floorDTO, VenueHallSection sectionDTO, VenueHallRow rowsDTO, VenueHallSeat seatTemplate, PerformanceTime performanceTime, SeatInfo seatInfo, Integer price) {
        return Seat.builder()
                .performanceTime(performanceTime)
                .seatFloor(floorDTO.getFloor())
                .seatSection(sectionDTO.getSection())
                .seatRow(rowsDTO.getRow())
                .seatNumber(seatTemplate.getSeatNumber())
                .seatType(seatInfo)
                .price(price)
                .seatStatus(SeatStatus.AVAILABLE)
                .build();
    }

    @Transactional
    public void preprocessSeatDataWithNoAsync(Long performanceTimeId) {

        List<Seat> unreservationSeat = new ArrayList<>();

        PerformanceTime performanceTime = performanceTimeRepository.findById(performanceTimeId)
                .orElseThrow(() -> new EntityNotFoundException("해당 공연장(공연) 시간을 찾을 수 없습니다."));

        VenueHall venueHall = performanceTime.getVenueHall();
        Performance performance = performanceTime.getPerformance();

        Map<SeatInfo, Integer> priceMap = performance.getSeatPrices()
                .stream()
                .collect(
                        Collectors.toMap(
                                SeatPrice::getSeatInfo,
                                SeatPrice::getPrice
                        )
                );

        if(priceMap.isEmpty()) {
            System.out.println("SeatService.preprocessSeatData");
        }

        List<VenueHallFloor> floorList = venueHall.getFloorList();

        floorList.forEach(floorDTO -> {
            List<VenueHallSection> sections = floorDTO.getSections();
            sections.forEach(sectionDTO -> {
                List<VenueHallRow> rows = sectionDTO.getRows();
                rows.forEach(rowsDTO -> {
                    List<VenueHallSeat> seats = rowsDTO.getSeats();
                    SeatInfo seatInfo = seats.getFirst().getSeatInfo();
                    System.out.println("seatInfo = " + seatInfo);
                    Integer price = priceMap.get(seatInfo);
                    System.out.println("price = " + price);

                    seats.forEach(seatTemplate -> {
                        Seat seat = processSeat(floorDTO, sectionDTO, rowsDTO, seatTemplate, performanceTime, seatInfo, price);
                        unreservationSeat.add(seat);
                    });

                });
            });
        });

        repository.saveAll(unreservationSeat);
    }


}

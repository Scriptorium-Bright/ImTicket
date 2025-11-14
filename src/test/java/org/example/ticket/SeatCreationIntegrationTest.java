package org.example.ticket;

import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.performance.model.SeatPrice;
import org.example.ticket.performance.repository.PerformanceRepository;
import org.example.ticket.performance.repository.PerformanceTimeRepository;
import org.example.ticket.performance.request.PerformanceDetailRequest;
import org.example.ticket.performance.request.PerformanceTimeRequest;
import org.example.ticket.performance.request.SeatPriceRequest;
import org.example.ticket.performance.service.PerformanceService;
import org.example.ticket.performance.service.PerformanceTimeService;
import org.example.ticket.performance.service.SeatPriceService;
import org.example.ticket.reservation.model.Seat;
import org.example.ticket.reservation.repository.SeatRepository;
import org.example.ticket.reservation.service.SeatService;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.venue.dto.request.*;
import org.example.ticket.venue.model.Venue;
import org.example.ticket.venue.repository.VenueRepository;
import org.example.ticket.venue.service.VenueHallService;
import org.example.ticket.venue.service.VenueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@ActiveProfiles("test")
class SeatCreationIntegrationTest {

    @Autowired private VenueService venueService;
    @Autowired private VenueHallService venueHallService; // 템플릿 생성을 위해 주입
    @Autowired private VenueRepository venueRepository;
    @Autowired private PerformanceService performanceService;
    @Autowired private PerformanceRepository performanceRepository;
    @Autowired private SeatPriceService seatPriceService;
    @Autowired private PerformanceTimeService performanceTimeService;
    @Autowired private PerformanceTimeRepository performanceTimeRepository;
    @Autowired private SeatService seatService;
    @Autowired private SeatRepository seatRepository;

    @Test
    @DisplayName("공연장 등록부터 좌석 재고 생성까지의 전체 흐름 통합 테스트")
    void fullSeatCreationWorkflow_shouldSucceed() throws IOException, InterruptedException {
        // given: 테스트를 위한 데이터 준비 (Arrange)
        // 1. 공연장 및 홀 정보 DTO 생성
        VenueRequest venueRequest = VenueRequest.builder().name("테스트 공연장").address("서울시 테스트구").build();
        VenueHallRequest hallRequest = VenueHallRequest.builder().name("A홀g").totalSeats(5).build(); // 테스트할 좌석 수와 일치시킴

        // 2. 공연장 및 기본 홀 정보 저장
        venueService.insertVenue(venueRequest, List.of(hallRequest));
        Venue savedVenue = venueRepository.findAll().getFirst();
        Long hallId = venueRepository.findByVenueHallsId(savedVenue.getId());

        // 3. (★★★★★ 여기가 빠졌던 부분 ★★★★★) 좌석 배치도(템플릿) DTO 생성
        VenueHallSeatRequest row1Seats = VenueHallSeatRequest.builder().seatInfo(SeatInfo.VIP).startSeatNumber(1).endSeatNumber(3).build(); // 1,2,3번 좌석
        VenueHallRowRequest row1 = VenueHallRowRequest.builder().row(1).seats(List.of(row1Seats)).build();

        VenueHallSeatRequest row2Seats = VenueHallSeatRequest.builder().seatInfo(SeatInfo.S).startSeatNumber(1).endSeatNumber(2).build(); // 1,2번 좌석
        VenueHallRowRequest row2 = VenueHallRowRequest.builder().row(2).seats(List.of(row2Seats)).build();

        VenueHallSectionRequest sectionA = VenueHallSectionRequest.builder().section("A").rows(List.of(row1, row2)).build();
        VenueHallFloorRequest floor1 = VenueHallFloorRequest.builder().floor(1).section(List.of(sectionA)).build();
        List<VenueHallFloorRequest> layoutRequest = List.of(floor1);

        // 4. 공연 정보 DTO 생성
        PerformanceDetailRequest performanceRequest = PerformanceDetailRequest.builder()
                .title("테스트 공연")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(10))
                .build();

        // 5. 가격 정책 DTO 생성
        List<SeatPriceRequest> priceRequests = List.of(
                SeatPriceRequest.builder().seatInfo(SeatInfo.VIP).price(150000).build(),
                SeatPriceRequest.builder().seatInfo(SeatInfo.S).price(120000).build()
        );

        // when: 테스트하려는 로직 실행 (Act)
        // 6. 좌석 템플릿 등록
        venueHallService.allocateEmptySeatTemplate(hallId, layoutRequest);

        // 7. 공연 등록
        Long performanceId = performanceService.registerPerformance(performanceRequest, null);

        // 8. 가격 정책 등록
        seatPriceService.setSeatPrice(priceRequests, performanceId);

        // 9. 공연 회차 DTO 생성 및 등록
        List<PerformanceTimeRequest> timeRequests = List.of(
                PerformanceTimeRequest.builder()
                        .showDate(LocalDate.now().plusDays(5))
                        .showTime(LocalTime.of(19, 30))
                        .venueHallId(hallId)
                        .build()
        );
        performanceTimeService.allocatePerformanceTime(timeRequests, performanceId);
        Long performanceTimeId = performanceTimeRepository.findAll().getFirst().getId();

        // 10. 좌석 재고 생성 (핵심 테스트 대상)
        seatService.preprocessSeatData(performanceTimeId);

        // then: 결과 검증 (Assert)
        // 11. 생성된 좌석들이 DB에 올바르게 저장되었는지 확인
        List<Seat> createdSeats = seatRepository.findAllByPerformanceTimeId(performanceTimeId);

        Thread.sleep(10000);

        assertNotNull(createdSeats);
        // 이제 템플릿에 정의한 대로 정확한 좌석 수를 검증할 수 있다.
        assertEquals(5, createdSeats.size()); // 1행 3개(VIP) + 2행 2개(S) = 총 5석

        // 첫 번째 좌석(1층 A구역 1행 1번)의 상세 정보 검증
        Seat firstSeat = createdSeats.stream().filter(s -> s.getSeatRow() == 1 && s.getSeatNumber() == 1).findFirst().get();
        assertEquals(performanceTimeId, firstSeat.getPerformanceTime().getId());
        assertEquals(1, firstSeat.getSeatFloor());
        assertEquals("A", firstSeat.getSeatSection());
        assertEquals(SeatInfo.VIP, firstSeat.getSeatType());
        assertEquals(150000, firstSeat.getPrice()); // VIP 가격 정책이 올바르게 적용되었는지
        assertFalse(firstSeat.getIsReservation()); // 초기 상태가 '예약 가능'인지
    }


}
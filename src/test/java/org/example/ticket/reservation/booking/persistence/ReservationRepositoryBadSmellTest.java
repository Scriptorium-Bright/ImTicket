package org.example.ticket.reservation.booking.persistence;

import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.performance.repository.PerformanceRepository;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.domain.ReservedSeat;
import org.example.ticket.util.constant.ReservationStatus;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class ReservationRepositoryBadSmellTest {

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PerformanceRepository performanceRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Test
    @Disabled("리팩토링 후보를 드러내기 위한 고의 실패 테스트라 기본 테스트 게이트에서는 제외한다.")
    @DisplayName("배드스멜 테스트: 5단계 깊이의 과도한 JOIN FETCH로 인한 Network I/O 병목 확인")
    public void testExcessiveJoinFetchNetworkIO() {
        // given: Member, Performance, PerformanceTime, Seat 생성 후 Reservation 등록
        Member member = Member.builder()
                .walletAddress("0xTESTWALLET")
                .phoneNumber("01012345678")
                .smsVerified(true)
                .walletVerified(true)
                .role("ROLE_USER")
                .build();
        memberRepository.save(member);

        Performance performance = Performance.builder()
                .title("대규모 콘서트")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(1))
                .build();

        PerformanceTime performanceTime = PerformanceTime.builder()
                .performance(performance)
                .showDate(LocalDate.now())
                .showTime(java.time.LocalTime.NOON)
                .build();
        performance.getPerformanceTimes().add(performanceTime);
        performanceRepository.save(performance);

        Seat seat1 = Seat.builder()
                .performanceTime(performanceTime)
                .seatFloor(1)
                .seatSection("A")
                .seatRow(1)
                .seatNumber(1)
                .seatType(org.example.ticket.util.constant.SeatInfo.VIP)
                .price(10000)
                .isReservation(false)
                .seatStatus(SeatStatus.AVAILABLE)
                .build();
        Seat seat2 = Seat.builder()
                .performanceTime(performanceTime)
                .seatFloor(1)
                .seatSection("A")
                .seatRow(1)
                .seatNumber(2)
                .seatType(org.example.ticket.util.constant.SeatInfo.VIP)
                .price(10000)
                .isReservation(false)
                .seatStatus(SeatStatus.AVAILABLE)
                .build();
        seatRepository.save(seat1);
        seatRepository.save(seat2);

        Reservation reservation = Reservation.builder()
                .reservationCode(UUID.randomUUID().toString())
                .totalPrice(20000)
                .member(member)
                .reservationStatus(ReservationStatus.PENDING_PAYMENT)
                .expiredTime(LocalDateTime.now().plusMinutes(5))
                .build();

        ReservedSeat rs1 = ReservedSeat.builder().seat(seat1).reservation(reservation).build();
        ReservedSeat rs2 = ReservedSeat.builder().seat(seat2).reservation(reservation).build();
        reservation.getReservedSeats().add(rs1);
        reservation.getReservedSeats().add(rs2);

        reservationRepository.saveAndFlush(reservation);

        // when: findByIdWithDetails 호출 시, 5단계 깊이 JOIN FETCH 발생
        // SELECT r FROM Reservation r JOIN FETCH r.member m JOIN FETCH r.reservedSeats rs JOIN FETCH rs.seat s JOIN FETCH s.performanceTime pt JOIN FETCH pt.performance p
        Reservation foundReservation = reservationRepository.findByIdWithDetails(reservation.getId()).orElse(null);

        // then: 데이터가 로딩되긴 하지만, 테스트를 '실패' 상태로 만들어 리팩토링이 필요함을 알림.
        assertNotNull(foundReservation);
        
        fail("이 테스트는 배드스멜을 고의로 실패시킵니다. " +
                "이유: N+1을 방지하기 위해 걸어둔 5단계 JOIN FETCH가 " +
                "DB에서 애플리케이션으로 중복된 Member, Performance 데이터를 폭발적으로 전송시켜 Network I/O 병목을 유발합니다. " +
                "BatchSize나 쿼리 분리를 통한 최적화가 필요합니다.");
    }
}

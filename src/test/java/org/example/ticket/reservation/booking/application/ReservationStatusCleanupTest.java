package org.example.ticket.reservation.booking.application;

import org.example.ticket.ApiIntegrationTestBase;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.persistence.ReservationRepository;
import org.example.ticket.util.constant.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReservationStatusCleanupTest extends ApiIntegrationTestBase {

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    @DisplayName("만료 예약 정리: 결제 완료된 예약(SUCCESS)은 만료 시간이 지나도 삭제하지 않는다")
    public void cleanupExpiredReservationDoesNotDeleteSuccessfulReservation() {
        // given: 결제가 완료된(SUCCESS) 예약을 만료 시간이 지난 상태로 DB에 저장
        Member member = Member.builder()
                .walletAddress("0xTestCleanup")
                .role("USER")
                .build();
        memberRepository.save(member);

        Reservation reservation = Reservation.builder()
                .reservationCode("TEST-CLEANUP-123")
                .totalPrice(10000)
                .member(member)
                .reservationStatus(ReservationStatus.SUCCESS) // 이미 성공함!
                .reservationDateTime(LocalDateTime.now().minusHours(2))
                .expiredTime(LocalDateTime.now().minusHours(1)) // 만료 시간이 과거
                .build();
        reservationRepository.save(reservation);

        // when: 스케줄러가 돌아가면
        reservationService.cleanupExpiredReservation();

        boolean isExist = reservationRepository.findById(reservation.getId()).isPresent();
        assertTrue(isExist, "SUCCESS 예약은 cleanup 대상이 아니므로 남아 있어야 합니다.");
    }
}

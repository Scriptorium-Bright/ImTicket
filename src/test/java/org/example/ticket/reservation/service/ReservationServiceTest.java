package org.example.ticket.reservation.service;

import jakarta.persistence.EntityNotFoundException;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.reservation.model.Reservation;
import org.example.ticket.reservation.repository.ReservationRepository;
import org.example.ticket.reservation.request.ReservationCheckRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.example.ticket.util.constant.ReservationStatus.PENDING_PAYMENT;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private SeatService seatService;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    void confirmReservationRejectsDifferentOwner() {
        Member owner = Member.builder()
                .walletAddress("0xowner")
                .phoneNumber("01012345678")
                .nickname("owner")
                .smsVerified(true)
                .walletVerified(true)
                .role("ROLE_USER")
                .build();
        Reservation reservation = Reservation.builder()
                .id(1L)
                .member(owner)
                .reservationStatus(PENDING_PAYMENT)
                .build();
        when(reservationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(reservation));

        assertThrows(EntityNotFoundException.class,
                () -> reservationService.confirmReservation("0xother", new ReservationCheckRequest(1L)));
    }
}

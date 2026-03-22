package org.example.ticket.entry.service;

import org.example.ticket.entry.repository.EntryLogRepository;
import org.example.ticket.member.model.Member;
import org.example.ticket.reservation.model.Reservation;
import org.example.ticket.reservation.repository.ReservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketEntryServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private EntryLogRepository entryLogRepository;

    @InjectMocks
    private TicketEntryService ticketEntryService;

    @Test
    void generateEntryTokenRejectsForeignReservationOwner() {
        Member owner = Member.builder()
                .walletAddress("0xowner")
                .role("ROLE_USER")
                .build();
        Reservation reservation = Reservation.builder()
                .id(1L)
                .member(owner)
                .build();
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(reservation));

        assertThrows(IllegalArgumentException.class,
                () -> ticketEntryService.generateEntryToken("0xother", 1L));
    }
}

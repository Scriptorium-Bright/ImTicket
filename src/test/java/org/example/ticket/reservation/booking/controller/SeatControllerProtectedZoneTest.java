package org.example.ticket.reservation.booking.controller;

import org.example.ticket.reservation.booking.dto.response.SeatResponse;
import org.example.ticket.reservation.booking.service.SeatService;
import org.example.ticket.reservation.waitingroom.pass.WaitingRoomPassClaims;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomAccess;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomAccessDecision;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomAccessGuard;
import org.example.ticket.security.principal.MetamaskUserDetails;
import org.example.ticket.member.model.Member;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeatControllerProtectedZoneTest {

    private static final long PERFORMANCE_TIME_ID = 7L;
    private static final long MEMBER_ID = 12L;
    private static final UUID TICKET_ID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @Test
    void rejectsProtectedSeatMapBeforeSeatRepositoryService() {
        SeatService seatService = mock(SeatService.class);
        WaitingRoomAccessGuard accessGuard = mock(WaitingRoomAccessGuard.class);
        when(accessGuard.authorize(PERFORMANCE_TIME_ID, MEMBER_ID, null))
                .thenThrow(new org.example.ticket.common.exception.BusinessException(
                        org.example.ticket.reservation.waitingroom.constant.WaitingRoomErrorCode.WAITING_ROOM_PASS_REQUIRED
                ));
        SeatController controller = new SeatController(seatService, accessGuard);

        assertThatThrownBy(() -> controller.viewSeatMap(user(), PERFORMANCE_TIME_ID, null))
                .isInstanceOf(org.example.ticket.common.exception.BusinessException.class);

        verify(seatService, never()).viewSeatMap(PERFORMANCE_TIME_ID);
    }

    @Test
    void queriesSeatMapOnlyAfterActivePassGuard() {
        SeatService seatService = mock(SeatService.class);
        WaitingRoomAccessGuard accessGuard = mock(WaitingRoomAccessGuard.class);
        String pass = "signed-pass";
        when(accessGuard.authorize(PERFORMANCE_TIME_ID, MEMBER_ID, pass)).thenReturn(activeAccess());
        when(seatService.viewSeatMap(PERFORMANCE_TIME_ID)).thenReturn(List.of());
        SeatController controller = new SeatController(seatService, accessGuard);

        controller.viewSeatMap(user(), PERFORMANCE_TIME_ID, pass);

        verify(accessGuard).authorize(PERFORMANCE_TIME_ID, MEMBER_ID, pass);
        verify(seatService).viewSeatMap(PERFORMANCE_TIME_ID);
    }

    private MetamaskUserDetails user() {
        return new MetamaskUserDetails(Member.builder()
                .id(MEMBER_ID)
                .walletAddress("0xowner")
                .role("ROLE_USER")
                .build());
    }

    private WaitingRoomAccess activeAccess() {
        return new WaitingRoomAccess(
                WaitingRoomAccessDecision.ACTIVE,
                new WaitingRoomPassClaims(
                        TICKET_ID,
                        MEMBER_ID,
                        PERFORMANCE_TIME_ID,
                        Instant.parse("2026-08-15T00:00:00Z"),
                        Instant.parse("2026-08-15T00:05:00Z")
                )
        );
    }
}

package org.example.ticket.reservation.booking.api;

import org.example.ticket.member.model.Member;
import org.example.ticket.reservation.booking.api.ReservationRequest;
import org.example.ticket.reservation.booking.api.ReservationCreateResponse;
import org.example.ticket.reservation.booking.application.ReservationPreReserveService;
import org.example.ticket.security.principal.MetamaskUserDetails;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationControllerIdempotencyTest {

    @Test
    void forwardsWalletIdempotencyHeaderAndBodyToPreReserveFacade() {
        ReservationPreReserveService preReserveService = mock(ReservationPreReserveService.class);
        ReservationController controller = new ReservationController(preReserveService);
        ReservationRequest request = new ReservationRequest(1L, List.of(11L));
        ReservationCreateResponse response = ReservationCreateResponse.builder()
                .id(10L)
                .responses(List.of())
                .build();
        String key = "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1";
        when(preReserveService.preReserve("0xowner", key, request)).thenReturn(response);

        var result = controller.registerReservation(user(), key, request);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getData()).isSameAs(response);
        verify(preReserveService).preReserve("0xowner", key, request);
    }

    private MetamaskUserDetails user() {
        return new MetamaskUserDetails(Member.builder()
                .walletAddress("0xowner")
                .role("ROLE_USER")
                .build());
    }
}

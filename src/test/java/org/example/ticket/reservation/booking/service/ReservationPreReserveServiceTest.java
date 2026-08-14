package org.example.ticket.reservation.booking.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.dto.request.ReservationRequest;
import org.example.ticket.reservation.booking.dto.response.ReservationCreateResponse;
import org.example.ticket.reservation.booking.util.ReservationRequestHasher;
import org.example.ticket.reservation.booking.util.idempotency.ReservationIntentFingerprint;
import org.example.ticket.reservation.waitingroom.constant.WaitingRoomErrorCode;
import org.example.ticket.reservation.waitingroom.pass.WaitingRoomPassClaims;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomAccess;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomAccessDecision;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomAccessGuard;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationPreReserveServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final String KEY = "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1";
    private static final UUID TICKET_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    @Mock
    private ReservationClaimExecutionService claimExecutionService;

    @Mock
    private WaitingRoomAccessGuard accessGuard;

    @Mock
    private WaitingRoomService waitingRoomService;

    private final ReservationRequestHasher requestHasher = new ReservationRequestHasher();
    private ReservationPreReserveService preReserveService;

    @BeforeEach
    void setUp() {
        preReserveService = new ReservationPreReserveService(
                requestHasher,
                claimExecutionService,
                accessGuard,
                waitingRoomService,
                new SimpleMeterRegistry()
        );
    }

    @Test
    void normalizesInputAndDelegatesMemberIdWithoutMemberRepositoryLookup() {
        ReservationRequest request = new ReservationRequest(1L, List.of(2L, 1L));
        ReservationIntentFingerprint fingerprint = requestHasher.fingerprint(request);
        ReservationCreateResponse response = response();
        when(accessGuard.authorize(1L, MEMBER_ID, null)).thenReturn(bypass());
        when(claimExecutionService.execute(MEMBER_ID, KEY, request, fingerprint)).thenReturn(response);

        assertThat(preReserveService.preReserve(MEMBER_ID, KEY, null, request)).isSameAs(response);

        verify(claimExecutionService).execute(MEMBER_ID, KEY, request, fingerprint);
        verifyNoInteractions(waitingRoomService);
    }

    @Test
    void activeAccessCompletesWaitingRoomOnlyAfterReservationResponse() {
        ReservationRequest request = new ReservationRequest(1L, List.of(1L));
        ReservationIntentFingerprint fingerprint = requestHasher.fingerprint(request);
        ReservationCreateResponse response = response();
        WaitingRoomAccess active = new WaitingRoomAccess(
                WaitingRoomAccessDecision.ACTIVE,
                new WaitingRoomPassClaims(
                        TICKET_ID,
                        MEMBER_ID,
                        1L,
                        Instant.parse("2026-08-15T00:00:00Z"),
                        Instant.parse("2026-08-15T00:05:00Z")
                )
        );
        when(accessGuard.authorize(1L, MEMBER_ID, "entry-pass")).thenReturn(active);
        when(claimExecutionService.execute(MEMBER_ID, KEY, request, fingerprint)).thenReturn(response);

        assertThat(preReserveService.preReserve(MEMBER_ID, KEY, "entry-pass", request)).isSameAs(response);

        verify(waitingRoomService).complete(1L, MEMBER_ID, TICKET_ID);
    }

    @Test
    void replayOnlyAccessReadsFinalSnapshotAndSkipsNewClaimAndComplete() {
        ReservationRequest request = new ReservationRequest(1L, List.of(1L));
        ReservationIntentFingerprint fingerprint = requestHasher.fingerprint(request);
        ReservationCreateResponse response = response();
        when(accessGuard.authorize(1L, MEMBER_ID, "expired-pass")).thenReturn(new WaitingRoomAccess(
                WaitingRoomAccessDecision.REPLAY_ONLY,
                new WaitingRoomPassClaims(
                        TICKET_ID,
                        MEMBER_ID,
                        1L,
                        Instant.parse("2026-08-14T23:55:00Z"),
                        Instant.parse("2026-08-15T00:00:00Z")
                )
        ));
        when(claimExecutionService.replayOnly(MEMBER_ID, KEY, fingerprint)).thenReturn(response);

        assertThat(preReserveService.preReserve(MEMBER_ID, KEY, "expired-pass", request)).isSameAs(response);

        verify(claimExecutionService, never()).execute(any(), any(), any(), any());
        verifyNoInteractions(waitingRoomService);
    }

    @Test
    void protectedZoneRejectionStopsBeforeClaimExecution() {
        ReservationRequest request = new ReservationRequest(1L, List.of(1L));
        when(accessGuard.authorize(1L, MEMBER_ID, null))
                .thenThrow(new BusinessException(WaitingRoomErrorCode.WAITING_ROOM_PASS_REQUIRED));

        assertThatThrownBy(() -> preReserveService.preReserve(MEMBER_ID, KEY, null, request))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(claimExecutionService, waitingRoomService);
    }

    @Test
    void invalidRequestStopsBeforeProtectedZone() {
        ReservationRequest request = new ReservationRequest(null, List.of(1L));

        assertThatThrownBy(() -> preReserveService.preReserve(MEMBER_ID, KEY, null, request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ReservationErrorCode.PERFORMANCE_TIME_REQUIRED);

        verifyNoInteractions(accessGuard, claimExecutionService, waitingRoomService);
    }

    @Test
    void completeFailureDoesNotMaskCommittedReservationResponse() {
        ReservationRequest request = new ReservationRequest(1L, List.of(1L));
        ReservationIntentFingerprint fingerprint = requestHasher.fingerprint(request);
        ReservationCreateResponse response = response();
        WaitingRoomAccess active = new WaitingRoomAccess(
                WaitingRoomAccessDecision.ACTIVE,
                new WaitingRoomPassClaims(
                        TICKET_ID,
                        MEMBER_ID,
                        1L,
                        Instant.parse("2026-08-15T00:00:00Z"),
                        Instant.parse("2026-08-15T00:05:00Z")
                )
        );
        when(accessGuard.authorize(1L, MEMBER_ID, "entry-pass")).thenReturn(active);
        when(claimExecutionService.execute(MEMBER_ID, KEY, request, fingerprint)).thenReturn(response);
        when(waitingRoomService.complete(1L, MEMBER_ID, TICKET_ID))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThat(preReserveService.preReserve(MEMBER_ID, KEY, "entry-pass", request)).isSameAs(response);
    }

    private WaitingRoomAccess bypass() {
        return new WaitingRoomAccess(WaitingRoomAccessDecision.BYPASS, null);
    }

    private ReservationCreateResponse response() {
        return ReservationCreateResponse.builder()
                .id(20L)
                .totalPrice(10000)
                .orderUid("reservation-20")
                .expiredTime(LocalDateTime.now().plusMinutes(7))
                .responses(List.of())
                .build();
    }
}

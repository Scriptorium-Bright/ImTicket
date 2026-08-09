package org.example.ticket.reservation.service;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.member.model.Member;
import org.example.ticket.reservation.exception.ReservationErrorCode;
import org.example.ticket.reservation.model.Reservation;
import org.example.ticket.reservation.model.ReservationIdempotency;
import org.example.ticket.reservation.model.ReservationIdempotencyStatus;
import org.example.ticket.reservation.repository.ReservationIdempotencyRepository;
import org.example.ticket.reservation.repository.ReservationRepository;
import org.example.ticket.reservation.request.ReservationRequest;
import org.example.ticket.reservation.response.ReservationCreateResponse;
import org.example.ticket.reservation.util.ReservationResponseSnapshotCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationIdempotentCreationServiceTest {

    @Mock
    private ReservationIdempotencyRepository idempotencyRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationService reservationService;

    @Mock
    private ReservationResponseSnapshotCodec snapshotCodec;

    @InjectMocks
    private ReservationIdempotentCreationService creationService;

    @Test
    void reservationAndSucceededSnapshotAreUpdatedInOneCreationPath() {
        Member member = Member.builder().id(7L).walletAddress("0xowner").role("ROLE_USER").build();
        ReservationIdempotency claim = processingClaim(member, "token-1");
        ReservationRequest request = new ReservationRequest(10L, List.of(1L));
        ReservationCreateResponse response = ReservationCreateResponse.builder()
                .id(20L)
                .expiredTime(LocalDateTime.now().plusMinutes(7))
                .responses(List.of())
                .build();
        Reservation reservation = Reservation.builder().id(20L).build();
        when(idempotencyRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(claim));
        when(reservationService.createReservationWithinTransaction("0xowner", request)).thenReturn(response);
        when(snapshotCodec.encode(response)).thenReturn("snapshot");
        when(reservationRepository.getReferenceById(20L)).thenReturn(reservation);

        assertThat(creationService.create(
                7L, "0xowner", request, "hash-1", 11L, "token-1"
        )).isSameAs(response);

        assertThat(claim.getStatus()).isEqualTo(ReservationIdempotencyStatus.SUCCEEDED);
        assertThat(claim.getReservation()).isSameAs(reservation);
        assertThat(claim.getResponseSchemaVersion())
                .isEqualTo(ReservationResponseSnapshotCodec.CURRENT_SCHEMA_VERSION);
        assertThat(claim.getResponsePayload()).isEqualTo("snapshot");
    }

    @Test
    void staleAttemptTokenIsFencedBeforeSeatMutation() {
        Member member = Member.builder().id(7L).walletAddress("0xowner").role("ROLE_USER").build();
        ReservationIdempotency claim = processingClaim(member, "new-token");
        ReservationRequest request = new ReservationRequest(10L, List.of(1L));
        when(idempotencyRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> creationService.create(
                7L, "0xowner", request, "hash-1", 11L, "old-token"
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ReservationErrorCode.IDEMPOTENCY_PROCESSING));

        verify(reservationService, never()).createReservationWithinTransaction("0xowner", request);
    }

    private ReservationIdempotency processingClaim(Member member, String token) {
        return ReservationIdempotency.builder()
                .id(11L)
                .member(member)
                .idempotencyKey("a0ebc4c9-8d82-47af-8127-1fc3d27e47a1")
                .requestHash("hash-1")
                .status(ReservationIdempotencyStatus.PROCESSING)
                .attemptToken(token)
                .leaseExpiresAt(LocalDateTime.now().plusSeconds(30))
                .build();
    }
}

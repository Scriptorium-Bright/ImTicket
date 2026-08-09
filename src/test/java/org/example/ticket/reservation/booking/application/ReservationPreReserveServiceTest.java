package org.example.ticket.reservation.booking.application;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.reservation.booking.concurrency.SeatAdmissionService;
import org.example.ticket.reservation.booking.application.ReservationClaimSnapshot;
import org.example.ticket.reservation.booking.domain.ReservationErrorCode;
import org.example.ticket.reservation.booking.domain.ReservationIdempotencyStatus;
import org.example.ticket.reservation.booking.api.ReservationRequest;
import org.example.ticket.reservation.booking.api.ReservationCreateResponse;
import org.example.ticket.reservation.booking.support.ReservationRequestHasher;
import org.example.ticket.reservation.booking.support.ReservationResponseSnapshotCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationPreReserveServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long CLAIM_ID = 11L;
    private static final String WALLET = "0xowner";
    private static final String KEY = "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1";

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private ReservationIdempotencyTransactionService transactionService;

    @Mock
    private ReservationIdempotentCreationService creationService;

    @Mock
    private ReservationResponseSnapshotCodec snapshotCodec;

    @Mock
    private SeatAdmissionService seatAdmissionService;

    private final ReservationRequestHasher requestHasher = new ReservationRequestHasher();
    private ReservationPreReserveService preReserveService;

    @BeforeEach
    void setUp() {
        preReserveService = new ReservationPreReserveService(
                memberRepository,
                requestHasher,
                transactionService,
                creationService,
                snapshotCodec,
                seatAdmissionService,
                30
        );
        when(memberRepository.findIdByWalletAddressIgnoreCase(WALLET)).thenReturn(Optional.of(MEMBER_ID));
    }

    @Test
    void newClaimRunsAdmissionThenCreation() {
        stubAdmissionPassThrough();
        ReservationRequest request = request(1L);
        String hash = requestHasher.fingerprint(request).requestHash();
        ReservationCreateResponse response = response();
        when(transactionService.createClaim(
                eq(MEMBER_ID), eq(KEY), eq(hash), any(String.class), any(LocalDateTime.class)))
                .thenAnswer(invocation -> claim(
                        ReservationIdempotencyStatus.PROCESSING,
                        invocation.getArgument(3),
                        invocation.getArgument(4),
                        hash,
                        null,
                        null
                ));
        when(creationService.create(
                eq(MEMBER_ID), eq(WALLET), eq(request), eq(hash), eq(CLAIM_ID), any(String.class)))
                .thenReturn(response);

        assertThat(preReserveService.preReserve(WALLET, KEY, request)).isSameAs(response);

        verify(seatAdmissionService).execute(eq(request), any());
        verify(creationService).create(
                eq(MEMBER_ID), eq(WALLET), eq(request), eq(hash), eq(CLAIM_ID), any(String.class));
    }

    @Test
    void successfulReplayBypassesAdmissionAndCreation() {
        ReservationRequest request = request(1L);
        String hash = requestHasher.fingerprint(request).requestHash();
        ReservationCreateResponse response = response();
        when(transactionService.createClaim(
                eq(MEMBER_ID), eq(KEY), eq(hash), any(String.class), any(LocalDateTime.class)))
                .thenThrow(duplicateKeyException());
        when(transactionService.findExisting(MEMBER_ID, KEY))
                .thenReturn(Optional.of(claim(
                        ReservationIdempotencyStatus.SUCCEEDED,
                        "old-token",
                        LocalDateTime.now().minusSeconds(1),
                        hash,
                        1,
                        "snapshot"
                )));
        when(snapshotCodec.decode(1, "snapshot")).thenReturn(response);

        assertThat(preReserveService.preReserve(WALLET, KEY, request)).isSameAs(response);

        verifyNoInteractions(creationService);
        verify(seatAdmissionService, never()).execute(any(), any());
    }

    @Test
    void processingReplayReturnsConflictBeforeAdmission() {
        ReservationRequest request = request(1L);
        String hash = requestHasher.fingerprint(request).requestHash();
        stubDuplicateThenExisting(claim(
                ReservationIdempotencyStatus.PROCESSING,
                "old-token",
                LocalDateTime.now().plusSeconds(30),
                hash,
                null,
                null
        ), hash);

        assertThatThrownBy(() -> preReserveService.preReserve(WALLET, KEY, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.IDEMPOTENCY_PROCESSING));

        verify(seatAdmissionService, never()).execute(any(), any());
    }

    @Test
    void differentBodyWithSameKeyReturnsConflict() {
        ReservationRequest request = request(1L);
        String hash = requestHasher.fingerprint(request).requestHash();
        stubDuplicateThenExisting(claim(
                ReservationIdempotencyStatus.FAILED_RETRYABLE,
                "old-token",
                LocalDateTime.now().minusSeconds(1),
                "different-hash",
                null,
                null
        ), hash);

        assertThatThrownBy(() -> preReserveService.preReserve(WALLET, KEY, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void failedClaimCanBeReclaimedAndRetriedWithSameKey() {
        stubAdmissionPassThrough();
        ReservationRequest request = request(1L);
        String hash = requestHasher.fingerprint(request).requestHash();
        ReservationCreateResponse response = response();
        stubDuplicateThenExisting(claim(
                ReservationIdempotencyStatus.FAILED_RETRYABLE,
                "old-token",
                LocalDateTime.now().minusSeconds(1),
                hash,
                null,
                null
        ), hash);
        when(transactionService.tryReclaim(
                eq(CLAIM_ID), eq(hash), any(String.class), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(true);
        when(creationService.create(
                eq(MEMBER_ID), eq(WALLET), eq(request), eq(hash), eq(CLAIM_ID), any(String.class)))
                .thenReturn(response);

        assertThat(preReserveService.preReserve(WALLET, KEY, request)).isSameAs(response);
        verify(seatAdmissionService).execute(eq(request), any());
    }

    @Test
    void admissionFailureMarksClaimRetryableWithoutMasking429() {
        ReservationRequest request = request(1L);
        String hash = requestHasher.fingerprint(request).requestHash();
        AtomicReference<String> token = new AtomicReference<>();
        when(transactionService.createClaim(
                eq(MEMBER_ID), eq(KEY), eq(hash), any(String.class), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    token.set(invocation.getArgument(3));
                    return claim(
                            ReservationIdempotencyStatus.PROCESSING,
                            token.get(),
                            LocalDateTime.now().plusSeconds(30),
                            hash,
                            null,
                            null
                    );
                });
        doThrow(new BusinessException(ReservationErrorCode.SEAT_ADMISSION_REJECTED))
                .when(seatAdmissionService).execute(eq(request), any());

        assertThatThrownBy(() -> preReserveService.preReserve(WALLET, KEY, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.SEAT_ADMISSION_REJECTED));
        verify(transactionService).markFailedIfOwned(
                eq(CLAIM_ID),
                eq(token.get()),
                eq("SEAT_ADMISSION_REJECTED"),
                any(LocalDateTime.class)
        );
    }

    @Test
    void unexpectedFailureStaysProcessingUntilLeaseInsteadOfImmediateRetry() {
        ReservationRequest request = request(1L);
        String hash = requestHasher.fingerprint(request).requestHash();
        when(transactionService.createClaim(
                eq(MEMBER_ID), eq(KEY), eq(hash), any(String.class), any(LocalDateTime.class)))
                .thenAnswer(invocation -> claim(
                        ReservationIdempotencyStatus.PROCESSING,
                        invocation.getArgument(3),
                        invocation.getArgument(4),
                        hash,
                        null,
                        null
                ));
        doThrow(new IllegalStateException("unexpected"))
                .when(seatAdmissionService).execute(eq(request), any());

        assertThatThrownBy(() -> preReserveService.preReserve(WALLET, KEY, request))
                .isInstanceOf(IllegalStateException.class);
        verify(transactionService, never()).markFailedIfOwned(
                any(), any(), any(), any()
        );
    }

    private void stubAdmissionPassThrough() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .when(seatAdmissionService).execute(any(ReservationRequest.class), any());
    }

    private void stubDuplicateThenExisting(
            ReservationClaimSnapshot existing,
            String hash
    ) {
        when(transactionService.createClaim(
                eq(MEMBER_ID), eq(KEY), eq(hash), any(String.class), any(LocalDateTime.class)))
                .thenThrow(duplicateKeyException());
        when(transactionService.findExisting(MEMBER_ID, KEY)).thenReturn(Optional.of(existing));
    }

    private ReservationClaimSnapshot claim(
            ReservationIdempotencyStatus status,
            String token,
            LocalDateTime leaseExpiresAt,
            String requestHash,
            Integer schemaVersion,
            String payload
    ) {
        return new ReservationClaimSnapshot(
                CLAIM_ID,
                MEMBER_ID,
                requestHash,
                status,
                token,
                leaseExpiresAt,
                schemaVersion,
                payload
        );
    }

    private DataIntegrityViolationException duplicateKeyException() {
        return new DataIntegrityViolationException(
                "duplicate claim",
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry for key 'uk_reservation_idempotency_member_key'",
                        "23000",
                        1062
                )
        );
    }

    private ReservationRequest request(Long seatId) {
        return new ReservationRequest(1L, List.of(seatId));
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

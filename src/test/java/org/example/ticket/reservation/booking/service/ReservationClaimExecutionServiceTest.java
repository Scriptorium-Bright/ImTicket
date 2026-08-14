package org.example.ticket.reservation.booking.service;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.domain.ReservationIdempotencyStatus;
import org.example.ticket.reservation.booking.dto.ReservationClaimSnapshot;
import org.example.ticket.reservation.booking.dto.request.ReservationRequest;
import org.example.ticket.reservation.booking.dto.response.ReservationCreateResponse;
import org.example.ticket.reservation.booking.util.ReservationFailureClassifier;
import org.example.ticket.reservation.booking.util.ReservationFailureSnapshotCodec;
import org.example.ticket.reservation.booking.util.ReservationResponseSnapshotCodec;
import org.example.ticket.reservation.booking.util.admission.SeatAdmissionService;
import org.example.ticket.reservation.booking.util.idempotency.ReservationIntentFingerprint;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationClaimExecutionServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long CLAIM_ID = 11L;
    private static final String KEY = "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1";
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Mock
    private ReservationIdempotencyTransactionService transactionService;

    @Mock
    private ReservationIdempotentCreationService creationService;

    @Mock
    private SeatAdmissionService seatAdmissionService;

    @Mock
    private ReservationResponseSnapshotCodec responseSnapshotCodec;

    private final ReservationFailureClassifier failureClassifier = new ReservationFailureClassifier();
    private final ReservationFailureSnapshotCodec failureSnapshotCodec = new ReservationFailureSnapshotCodec();
    private ReservationClaimExecutionService executionService;

    @BeforeEach
    void setUp() {
        executionService = new ReservationClaimExecutionService(
                transactionService,
                creationService,
                seatAdmissionService,
                failureClassifier,
                responseSnapshotCodec,
                failureSnapshotCodec,
                30
        );
    }

    @Test
    void newClaimRunsAdmissionThenMemberIdCreation() {
        stubAdmissionPassThrough();
        ReservationRequest request = request();
        ReservationCreateResponse response = response();
        when(transactionService.createClaim(
                eq(MEMBER_ID), eq(KEY), eq(HASH), any(String.class), any(LocalDateTime.class)))
                .thenAnswer(invocation -> claim(
                        ReservationIdempotencyStatus.PROCESSING,
                        invocation.getArgument(3),
                        invocation.getArgument(4),
                        null,
                        null
                ));
        when(creationService.create(
                eq(MEMBER_ID), eq(request), eq(HASH), eq(CLAIM_ID), any(String.class)))
                .thenReturn(response);

        assertThat(executionService.execute(MEMBER_ID, KEY, request, fingerprint())).isSameAs(response);

        verify(seatAdmissionService).execute(eq(request), any());
        verify(creationService).create(
                eq(MEMBER_ID), eq(request), eq(HASH), eq(CLAIM_ID), any(String.class));
    }

    @Test
    void finalFailureIsStoredWithPublicCodeAndSchema() {
        stubAdmissionPassThrough();
        AtomicReference<String> token = stubNewProcessingClaim();
        doThrow(new BusinessException(ReservationErrorCode.SEAT_ALREADY_RESERVED))
                .when(creationService).create(
                        eq(MEMBER_ID), any(ReservationRequest.class), eq(HASH), eq(CLAIM_ID), any(String.class));

        assertThatThrownBy(() -> executionService.execute(MEMBER_ID, KEY, request(), fingerprint()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.SEAT_ALREADY_RESERVED));

        verify(transactionService).markFinalFailureIfOwned(
                eq(CLAIM_ID),
                eq(token.get()),
                eq("SEAT_ALREADY_RESERVED"),
                eq(ReservationFailureSnapshotCodec.CURRENT_SCHEMA_VERSION),
                any(LocalDateTime.class)
        );
    }

    @Test
    void successfulReplayReturnsStoredResponseWithoutAdmission() {
        ReservationCreateResponse response = response();
        ReservationClaimSnapshot succeeded = new ReservationClaimSnapshot(
                CLAIM_ID,
                MEMBER_ID,
                HASH,
                ReservationIdempotencyStatus.SUCCEEDED,
                "old-token",
                LocalDateTime.now().minusSeconds(1),
                1,
                "response-snapshot",
                null,
                null
        );
        stubDuplicateThenExisting(succeeded);
        when(responseSnapshotCodec.decode(1, "response-snapshot")).thenReturn(response);

        assertThat(executionService.execute(MEMBER_ID, KEY, request(), fingerprint())).isSameAs(response);

        verifyNoInteractions(seatAdmissionService, creationService);
    }

    @Test
    void retryableClaimCanBeReclaimedByOneNewAttempt() {
        stubAdmissionPassThrough();
        ReservationClaimSnapshot failed = claim(
                ReservationIdempotencyStatus.FAILED_RETRYABLE,
                "old-token",
                LocalDateTime.now().minusSeconds(1),
                null,
                "SEAT_ADMISSION_REJECTED"
        );
        stubDuplicateThenExisting(failed);
        when(transactionService.tryReclaim(
                eq(CLAIM_ID), eq(HASH), any(String.class), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(true);
        when(creationService.create(
                eq(MEMBER_ID), any(ReservationRequest.class), eq(HASH), eq(CLAIM_ID), any(String.class)))
                .thenReturn(response());

        assertThat(executionService.execute(MEMBER_ID, KEY, request(), fingerprint()).getId())
                .isEqualTo(20L);

        verify(seatAdmissionService).execute(any(ReservationRequest.class), any());
    }

    @Test
    void finalFailureReplayReturnsSameCodeWithoutReclaimOrAdmission() {
        stubDuplicateThenExisting(claim(
                ReservationIdempotencyStatus.FAILED_FINAL,
                "old-token",
                LocalDateTime.now().minusSeconds(1),
                ReservationFailureSnapshotCodec.CURRENT_SCHEMA_VERSION,
                "SEAT_ALREADY_RESERVED"
        ));

        assertThatThrownBy(() -> executionService.execute(MEMBER_ID, KEY, request(), fingerprint()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.SEAT_ALREADY_RESERVED));

        verify(transactionService, never()).tryReclaim(any(), any(), any(), any(), any());
        verifyNoInteractions(seatAdmissionService, creationService);
    }

    @Test
    void replayOnlyReturnsSucceededSnapshotWithoutCreatingOrReclaimingClaim() {
        ReservationCreateResponse response = response();
        ReservationClaimSnapshot succeeded = new ReservationClaimSnapshot(
                CLAIM_ID,
                MEMBER_ID,
                HASH,
                ReservationIdempotencyStatus.SUCCEEDED,
                "old-token",
                LocalDateTime.now().minusSeconds(1),
                1,
                "response-snapshot",
                null,
                null
        );
        when(transactionService.findExisting(MEMBER_ID, KEY)).thenReturn(Optional.of(succeeded));
        when(responseSnapshotCodec.decode(1, "response-snapshot")).thenReturn(response);

        assertThat(executionService.replayOnly(MEMBER_ID, KEY, fingerprint())).isSameAs(response);

        verify(transactionService).findExisting(MEMBER_ID, KEY);
        verify(transactionService, never()).createClaim(any(), any(), any(), any(), any());
        verify(transactionService, never()).tryReclaim(any(), any(), any(), any(), any());
        verifyNoInteractions(seatAdmissionService, creationService);
    }

    @Test
    void replayOnlyReplaysFinalFailureWithoutCreatingOrReclaimingClaim() {
        when(transactionService.findExisting(MEMBER_ID, KEY)).thenReturn(Optional.of(claim(
                ReservationIdempotencyStatus.FAILED_FINAL,
                "old-token",
                LocalDateTime.now().minusSeconds(1),
                ReservationFailureSnapshotCodec.CURRENT_SCHEMA_VERSION,
                "SEAT_ALREADY_RESERVED"
        )));

        assertThatThrownBy(() -> executionService.replayOnly(MEMBER_ID, KEY, fingerprint()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.SEAT_ALREADY_RESERVED));

        verify(transactionService, never()).createClaim(any(), any(), any(), any(), any());
        verify(transactionService, never()).tryReclaim(any(), any(), any(), any(), any());
        verifyNoInteractions(seatAdmissionService, creationService);
    }

    @Test
    void replayOnlyRejectsMissingOrRetryableSnapshot() {
        when(transactionService.findExisting(MEMBER_ID, KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executionService.replayOnly(MEMBER_ID, KEY, fingerprint()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.IDEMPOTENCY_REPLAY_ONLY));

        when(transactionService.findExisting(MEMBER_ID, KEY)).thenReturn(Optional.of(claim(
                ReservationIdempotencyStatus.FAILED_RETRYABLE,
                "old-token",
                LocalDateTime.now().minusSeconds(1),
                null,
                "SEAT_ADMISSION_REJECTED"
        )));

        assertThatThrownBy(() -> executionService.replayOnly(MEMBER_ID, KEY, fingerprint()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.IDEMPOTENCY_REPLAY_ONLY));
        verifyNoInteractions(seatAdmissionService, creationService);
    }

    @Test
    void replayOnlyRejectsDifferentRequestHashBeforeSnapshotDecode() {
        ReservationClaimSnapshot succeeded = new ReservationClaimSnapshot(
                CLAIM_ID,
                MEMBER_ID,
                "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
                ReservationIdempotencyStatus.SUCCEEDED,
                "old-token",
                LocalDateTime.now().minusSeconds(1),
                1,
                "response-snapshot",
                null,
                null
        );
        when(transactionService.findExisting(MEMBER_ID, KEY)).thenReturn(Optional.of(succeeded));

        assertThatThrownBy(() -> executionService.replayOnly(MEMBER_ID, KEY, fingerprint()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.IDEMPOTENCY_CONFLICT));
        verifyNoInteractions(responseSnapshotCodec, seatAdmissionService, creationService);
    }

    @Test
    void retryableFailureIsStoredForImmediateReclaim() {
        AtomicReference<String> token = stubNewProcessingClaim();
        doThrow(new BusinessException(ReservationErrorCode.SEAT_ADMISSION_REJECTED))
                .when(seatAdmissionService).execute(any(ReservationRequest.class), any());

        assertThatThrownBy(() -> executionService.execute(MEMBER_ID, KEY, request(), fingerprint()))
                .isInstanceOf(BusinessException.class);

        verify(transactionService).markRetryableFailureIfOwned(
                eq(CLAIM_ID),
                eq(token.get()),
                eq("SEAT_ADMISSION_REJECTED"),
                any(LocalDateTime.class)
        );
    }

    @Test
    void unknownFailureKeepsProcessingLease() {
        stubNewProcessingClaim();
        doThrow(new IllegalStateException("unexpected"))
                .when(seatAdmissionService).execute(any(ReservationRequest.class), any());

        assertThatThrownBy(() -> executionService.execute(MEMBER_ID, KEY, request(), fingerprint()))
                .isInstanceOf(IllegalStateException.class);

        verify(transactionService, never()).markRetryableFailureIfOwned(any(), any(), any(), any());
        verify(transactionService, never()).markFinalFailureIfOwned(any(), any(), any(), anyInt(), any());
    }

    private AtomicReference<String> stubNewProcessingClaim() {
        AtomicReference<String> token = new AtomicReference<>();
        when(transactionService.createClaim(
                eq(MEMBER_ID), eq(KEY), eq(HASH), any(String.class), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    token.set(invocation.getArgument(3));
                    return claim(
                            ReservationIdempotencyStatus.PROCESSING,
                            token.get(),
                            invocation.getArgument(4),
                            null,
                            null
                    );
                });
        return token;
    }

    private void stubAdmissionPassThrough() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .when(seatAdmissionService).execute(any(ReservationRequest.class), any());
    }

    private void stubDuplicateThenExisting(ReservationClaimSnapshot existing) {
        when(transactionService.createClaim(
                eq(MEMBER_ID), eq(KEY), eq(HASH), any(String.class), any(LocalDateTime.class)))
                .thenThrow(duplicateKeyException());
        when(transactionService.findExisting(MEMBER_ID, KEY)).thenReturn(Optional.of(existing));
    }

    private ReservationClaimSnapshot claim(
            ReservationIdempotencyStatus status,
            String token,
            LocalDateTime leaseExpiresAt,
            Integer failureSchemaVersion,
            String lastErrorCode
    ) {
        return new ReservationClaimSnapshot(
                CLAIM_ID,
                MEMBER_ID,
                HASH,
                status,
                token,
                leaseExpiresAt,
                null,
                null,
                failureSchemaVersion,
                lastErrorCode
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

    private ReservationIntentFingerprint fingerprint() {
        return new ReservationIntentFingerprint(
                "reservation-pre-reserve:v1",
                10L,
                List.of(1L),
                HASH
        );
    }

    private ReservationRequest request() {
        return new ReservationRequest(1L, List.of(1L));
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

package org.example.ticket.reservation.queue.service;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.dto.response.ReservationCreateResponse;
import org.example.ticket.reservation.booking.service.ReservationClaimExecutionService;
import org.example.ticket.reservation.booking.util.ReservationFailureClassifier;
import org.example.ticket.reservation.queue.config.ReservationQueueWorkerProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueStreamMessage;
import org.example.ticket.reservation.queue.dto.ReservationQueueSuccessResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueTerminalResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;
import org.example.ticket.reservation.queue.dto.ReservationQueueRetryResult;
import org.example.ticket.reservation.queue.exception.ReservationQueuePayloadException;
import org.example.ticket.reservation.queue.repository.ReservationQueueTerminalStore;
import org.example.ticket.reservation.queue.repository.ReservationQueueRetryStore;
import org.example.ticket.reservation.queue.repository.ReservationQueueWorkerStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationQueueProcessorTest {

    private static final String WORKER_ID = "worker-a";
    private static final String GROUP = "booking-workers";
    private static final Instant NOW = Instant.parse("2026-08-12T10:00:00Z");

    private final ReservationClaimExecutionService executionService = mock(ReservationClaimExecutionService.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final ReservationQueueTerminalStore terminalStore = mock(ReservationQueueTerminalStore.class);
    private final ReservationQueueRetryStore retryStore = mock(ReservationQueueRetryStore.class);
    private final ReservationQueueWorkerStore workerStore = mock(ReservationQueueWorkerStore.class);
    private final ReservationFailureClassifier failureClassifier = new ReservationFailureClassifier();
    private ReservationQueueProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ReservationQueueProcessor(
                executionService,
                memberRepository,
                failureClassifier,
                terminalStore,
                retryStore,
                workerStore,
                new ReservationQueueWorkerProperties(
                        true, GROUP, WORKER_ID, 1, 1,
                        Duration.ofMillis(10), Duration.ofMillis(10)
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void databaseSuccessIsStoredBeforeStreamAck() {
        ReservationQueueWorkItem item = QueueWorkerTestFixtures.item();
        ReservationCreateResponse response = response();
        when(executionService.execute(
                eq(7L),
                eq(item.payload().idempotencyKey().value()),
                any(),
                any()
        )).thenReturn(response);
        ReservationQueueSuccessResult result = new ReservationQueueSuccessResult(
                1, 20L, 10_000, "reservation-20", LocalDateTime.parse("2026-08-12T10:07:00")
        );
        when(terminalStore.completeSuccess(item, WORKER_ID, result, NOW))
                .thenReturn(ReservationQueueTerminalResult.COMPLETED);

        processor.handle(item, WORKER_ID);

        var order = inOrder(terminalStore, workerStore);
        order.verify(terminalStore).completeSuccess(item, WORKER_ID, result, NOW);
        order.verify(workerStore).acknowledge(item, GROUP);
    }

    @Test
    void publicFinalFailureIsStoredBeforeAckWithoutExceptionMessage() {
        ReservationQueueWorkItem item = QueueWorkerTestFixtures.item();
        when(executionService.execute(eq(7L), any(), any(), any()))
                .thenThrow(new BusinessException(ReservationErrorCode.SEAT_ALREADY_RESERVED));
        when(terminalStore.completeFinal(
                item, WORKER_ID, "SEAT_ALREADY_RESERVED", NOW
        )).thenReturn(ReservationQueueTerminalResult.COMPLETED);

        processor.handle(item, WORKER_ID);

        var order = inOrder(terminalStore, workerStore);
        order.verify(terminalStore).completeFinal(item, WORKER_ID, "SEAT_ALREADY_RESERVED", NOW);
        order.verify(workerStore).acknowledge(item, GROUP);
    }

    @Test
    void missingMemberForeignKeyFailureConvergesToPublicFinalState() {
        ReservationQueueWorkItem item = QueueWorkerTestFixtures.item();
        when(executionService.execute(eq(7L), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("member foreign key"));
        when(memberRepository.existsById(7L)).thenReturn(false);
        when(terminalStore.completeFinal(
                item, WORKER_ID, "RESERVATION_MEMBER_NOT_FOUND", NOW
        )).thenReturn(ReservationQueueTerminalResult.COMPLETED);

        processor.handle(item, WORKER_ID);

        verify(terminalStore).completeFinal(
                item, WORKER_ID, "RESERVATION_MEMBER_NOT_FOUND", NOW
        );
        verify(workerStore).acknowledge(item, GROUP);
    }

    @Test
    void retryableFailureIsScheduledBeforeStreamAck() {
        ReservationQueueWorkItem item = QueueWorkerTestFixtures.item();
        when(executionService.execute(eq(7L), any(), any(), any()))
                .thenThrow(new BusinessException(ReservationErrorCode.SEAT_ADMISSION_REJECTED));
        when(retryStore.schedule(
                item, WORKER_ID, "SEAT_ADMISSION_REJECTED", NOW
        )).thenReturn(ReservationQueueRetryResult.SCHEDULED);

        processor.handle(item, WORKER_ID);

        verify(terminalStore, never()).completeFinal(any(), any(), any(), any());
        var order = inOrder(retryStore, workerStore);
        order.verify(retryStore).schedule(item, WORKER_ID, "SEAT_ADMISSION_REJECTED", NOW);
        order.verify(workerStore).acknowledge(item, GROUP);
    }

    @Test
    void transientDatabaseFailureUsesStableRetryCode() {
        ReservationQueueWorkItem item = QueueWorkerTestFixtures.item();
        when(executionService.execute(eq(7L), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("database timeout detail"));
        when(retryStore.schedule(
                item, WORKER_ID, "QUEUE_TRANSIENT_PROCESSING_FAILURE", NOW
        )).thenReturn(ReservationQueueRetryResult.SCHEDULED);

        processor.handle(item, WORKER_ID);

        verify(retryStore).schedule(
                item, WORKER_ID, "QUEUE_TRANSIENT_PROCESSING_FAILURE", NOW
        );
        verify(workerStore).acknowledge(item, GROUP);
    }

    @Test
    void terminalWriteFailureDoesNotAckStream() {
        ReservationQueueWorkItem item = QueueWorkerTestFixtures.item();
        when(executionService.execute(eq(7L), any(), any(), any())).thenReturn(response());
        when(terminalStore.completeSuccess(any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("Redis timeout"));

        assertThatThrownBy(() -> processor.handle(item, WORKER_ID))
                .isInstanceOf(QueryTimeoutException.class);

        verify(workerStore, never()).acknowledge(any(ReservationQueueWorkItem.class), any());
    }

    @Test
    void unsupportedPayloadBecomesFinalThenRawMessageIsAcked() {
        ReservationQueueStreamMessage message = new ReservationQueueStreamMessage(
                42L,
                "1786356000000-0",
                Map.of(
                        "ticketId", QueueWorkerTestFixtures.TICKET_ID.toString(),
                        "ownerToken", QueueWorkerTestFixtures.OWNER_TOKEN.toString()
                )
        );
        ReservationQueuePayloadException exception = new ReservationQueuePayloadException(
                ReservationQueuePayloadException.Reason.UNSUPPORTED_SCHEMA,
                "schema 2"
        );
        when(terminalStore.failInvalid(
                message, "QUEUE_WORKER_PAYLOAD_UNSUPPORTED", NOW
        )).thenReturn(ReservationQueueTerminalResult.COMPLETED);

        processor.reject(message, WORKER_ID, exception);

        var order = inOrder(terminalStore, workerStore);
        order.verify(terminalStore).failInvalid(message, "QUEUE_WORKER_PAYLOAD_UNSUPPORTED", NOW);
        order.verify(workerStore).acknowledge(message, GROUP);
    }

    private ReservationCreateResponse response() {
        return ReservationCreateResponse.builder()
                .id(20L)
                .totalPrice(10_000)
                .orderUid("reservation-20")
                .expiredTime(LocalDateTime.parse("2026-08-12T10:07:00"))
                .responses(java.util.List.of())
                .build();
    }
}

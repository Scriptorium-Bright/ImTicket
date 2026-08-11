package org.example.ticket.reservation.queue.service;

import org.example.ticket.reservation.queue.repository.ReservationQueueAdmissionStore;
import org.example.ticket.reservation.queue.repository.ReservationQueueTicketStore;
import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.constant.ReservationQueueErrorCode;
import org.example.ticket.reservation.queue.constant.ReservationQueueStatus;
import org.example.ticket.reservation.queue.dto.ReservationQueueAdmissionCommand;
import org.example.ticket.reservation.queue.dto.ReservationQueueAdmissionResult;
import org.example.ticket.reservation.queue.dto.ReservationQueuePayload;
import org.example.ticket.reservation.queue.dto.ReservationQueueTicketSnapshot;
import org.example.ticket.reservation.queue.dto.ReservationQueueSuccessResult;
import org.example.ticket.reservation.queue.dto.request.ReservationQueueApiRequest;
import org.example.ticket.reservation.queue.dto.response.ReservationQueueEnqueueResponse;
import org.example.ticket.reservation.queue.dto.response.ReservationQueueStatusResponse;
import org.example.ticket.reservation.queue.util.ReservationQueueIdentityHasher;

import org.example.ticket.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationQueueServiceTest {

    private static final UUID TICKET_ID = UUID.fromString("f76f5ac8-a475-4e04-906a-1f54765f9770");
    private static final UUID OWNER_TOKEN = UUID.fromString("da64524f-ac82-45a8-9d38-4cd641b72343");
    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    private final ReservationQueueAdmissionStore admissionStore = mock(ReservationQueueAdmissionStore.class);
    private final ReservationQueueTicketStore ticketStore = mock(ReservationQueueTicketStore.class);
    private final ReservationQueueProperties properties = ReservationQueueProperties.defaults();

    private ReservationQueueService service;

    @BeforeEach
    void setUp() {
        service = new ReservationQueueService(
                admissionStore,
                ticketStore,
                new ReservationQueueIdentityHasher(),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> TICKET_ID
        );
    }

    @Test
    void canonicalizesRequestAndReturnsAcceptedTicket() {
        when(admissionStore.admit(any())).thenReturn(
                ReservationQueueAdmissionResult.accepted(TICKET_ID, 42L, 7L, "1786356000000-0")
        );

        ReservationQueueEnqueueResponse response = service.enqueue(
                42L,
                "  0xOwNeR  ",
                "A0EBC4C9-8D82-47AF-8127-1FC3D27E47A1",
                new ReservationQueueApiRequest(42L, List.of(3L, 1L))
        );

        assertThat(response.ticketId()).isEqualTo(TICKET_ID);
        assertThat(response.status()).isEqualTo(ReservationQueueStatus.WAITING);
        assertThat(response.replayed()).isFalse();
        assertThat(response.pollAfterMs()).isEqualTo(1_000L);
        assertThat(response.statusUrl()).isEqualTo(
                "/api/reservation/pre-reserve/queue/42/" + TICKET_ID
        );

        var command = org.mockito.ArgumentCaptor.forClass(ReservationQueueAdmissionCommand.class);
        verify(admissionStore).admit(command.capture());
        assertThat(command.getValue().ownerHash()).matches("[0-9a-f]{64}").doesNotContain("0xowner");
        assertThat(command.getValue().payload().schemaVersion()).isEqualTo(1);
        assertThat(command.getValue().payload().memberId()).isEqualTo(42L);
        assertThat(command.getValue().payload().idempotencyKey().value()).isEqualTo(validKey());
        assertThat(command.getValue().payload().idempotencyKey().hash()).matches("[0-9a-f]{64}");
        assertThat(command.getValue().payload().normalizedSeatIds()).containsExactly(1L, 3L);
        assertThat(command.getValue().enqueuedAt()).isEqualTo(NOW);
    }

    @Test
    void sameQueuedRequestReturnsExistingTicketAsReplay() {
        UUID existingTicket = UUID.fromString("e4f31fe2-ce93-4082-b9d1-7904c952bb8d");
        when(admissionStore.admit(any())).thenReturn(ReservationQueueAdmissionResult.existing(
                ReservationQueueAdmissionResult.Outcome.EXISTING,
                existingTicket,
                42L
        ));

        ReservationQueueEnqueueResponse response = service.enqueue(42L, "0xowner", validKey(), request());

        assertThat(response.ticketId()).isEqualTo(existingTicket);
        assertThat(response.replayed()).isTrue();
    }

    @Test
    void mapsAdmissionOutcomesToQueueErrors() {
        assertAdmissionError(
                ReservationQueueAdmissionResult.rejected(
                        ReservationQueueAdmissionResult.Outcome.QUEUE_FULL, 42L),
                ReservationQueueErrorCode.QUEUE_FULL
        );
        assertAdmissionError(
                ReservationQueueAdmissionResult.rejected(
                        ReservationQueueAdmissionResult.Outcome.IDEMPOTENCY_CONFLICT, 42L),
                ReservationQueueErrorCode.IDEMPOTENCY_CONFLICT
        );
        assertAdmissionError(
                ReservationQueueAdmissionResult.existing(
                        ReservationQueueAdmissionResult.Outcome.ENQUEUE_IN_PROGRESS, TICKET_ID, 42L),
                ReservationQueueErrorCode.ENQUEUE_IN_PROGRESS
        );
    }

    @Test
    void mapsRedisTimeoutToServiceUnavailable() {
        when(admissionStore.admit(any())).thenThrow(new QueryTimeoutException("Redis timeout"));

        assertBusinessError(
                () -> service.enqueue(42L, "0xowner", validKey(), request()),
                ReservationQueueErrorCode.QUEUE_UNAVAILABLE
        );
    }

    @Test
    void returnsOwnedWaitingTicketWithCurrentPosition() {
        ReservationQueueTicketSnapshot waiting = waitingSnapshot(ownerHash(), NOW.plusSeconds(600), 5L);
        when(ticketStore.find(42L, TICKET_ID)).thenReturn(Optional.of(waiting));

        ReservationQueueStatusResponse response = service.status("0xowner", 42L, TICKET_ID);

        assertThat(response.status()).isEqualTo(ReservationQueueStatus.WAITING);
        assertThat(response.position()).isEqualTo(5L);
        assertThat(response.enqueuedAt()).isEqualTo(NOW);
        assertThat(response.deadlineAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void returnsSucceededResultAndFinalPublicError() {
        ReservationQueueSuccessResult result = new ReservationQueueSuccessResult(
                1, 20L, 10_000, "reservation-20", LocalDateTime.parse("2026-08-10T10:07:00")
        );
        ReservationQueueTicketSnapshot succeeded = terminalSnapshot(
                ReservationQueueStatus.SUCCEEDED, result, null
        );
        ReservationQueueTicketSnapshot failed = terminalSnapshot(
                ReservationQueueStatus.FAILED_FINAL, null, "SEAT_ALREADY_RESERVED"
        );
        when(ticketStore.find(42L, TICKET_ID))
                .thenReturn(Optional.of(succeeded))
                .thenReturn(Optional.of(failed));

        ReservationQueueStatusResponse successResponse = service.status("0xowner", 42L, TICKET_ID);
        ReservationQueueStatusResponse failureResponse = service.status("0xowner", 42L, TICKET_ID);

        assertThat(successResponse.status()).isEqualTo(ReservationQueueStatus.SUCCEEDED);
        assertThat(successResponse.result()).isEqualTo(result);
        assertThat(successResponse.errorCode()).isNull();
        assertThat(failureResponse.status()).isEqualTo(ReservationQueueStatus.FAILED_FINAL);
        assertThat(failureResponse.result()).isNull();
        assertThat(failureResponse.errorCode()).isEqualTo("SEAT_ALREADY_RESERVED");
    }

    @Test
    void ownerMismatchAndMissingTicketShareNotFoundError() {
        when(ticketStore.find(42L, TICKET_ID))
                .thenReturn(Optional.of(waitingSnapshot("d".repeat(64), NOW.plusSeconds(600), 1L)))
                .thenReturn(Optional.empty());

        assertBusinessError(
                () -> service.status("0xowner", 42L, TICKET_ID),
                ReservationQueueErrorCode.TICKET_NOT_FOUND
        );
        assertBusinessError(
                () -> service.status("0xowner", 42L, TICKET_ID),
                ReservationQueueErrorCode.TICKET_NOT_FOUND
        );
    }

    @Test
    void explicitlyExpiresDueWaitingTicket() {
        ReservationQueueTicketSnapshot due = waitingSnapshot(ownerHash(), NOW, 1L);
        ReservationQueueTicketSnapshot expired = new ReservationQueueTicketSnapshot(
                TICKET_ID,
                42L,
                ownerHash(),
                OWNER_TOKEN,
                payload(),
                ReservationQueueStatus.EXPIRED,
                7L,
                null,
                NOW.minusSeconds(600),
                NOW
        );
        when(ticketStore.find(42L, TICKET_ID))
                .thenReturn(Optional.of(due))
                .thenReturn(Optional.of(expired));
        when(ticketStore.expireIfDue(42L, TICKET_ID, NOW)).thenReturn(true);

        assertBusinessError(
                () -> service.status("0xowner", 42L, TICKET_ID),
                ReservationQueueErrorCode.TICKET_EXPIRED
        );
        verify(ticketStore).expireIfDue(42L, TICKET_ID, NOW);
    }

    @Test
    void serviceHasNoJpaRepositoryDependency() {
        assertThat(ReservationQueueService.class.getDeclaredFields())
                .noneMatch(field -> org.springframework.data.repository.Repository.class
                        .isAssignableFrom(field.getType()));
    }

    private void assertAdmissionError(
            ReservationQueueAdmissionResult result,
            ReservationQueueErrorCode errorCode
    ) {
        when(admissionStore.admit(any())).thenReturn(result);
        assertBusinessError(() -> service.enqueue(42L, "0xowner", validKey(), request()), errorCode);
    }

    private void assertBusinessError(Runnable invocation, ReservationQueueErrorCode errorCode) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    private ReservationQueueTicketSnapshot waitingSnapshot(String ownerHash, Instant deadline, Long position) {
        return new ReservationQueueTicketSnapshot(
                TICKET_ID,
                42L,
                ownerHash,
                OWNER_TOKEN,
                payload(),
                ReservationQueueStatus.WAITING,
                7L,
                position,
                NOW,
                deadline
        );
    }

    private ReservationQueueTicketSnapshot terminalSnapshot(
            ReservationQueueStatus status,
            ReservationQueueSuccessResult result,
            String errorCode
    ) {
        return new ReservationQueueTicketSnapshot(
                TICKET_ID,
                42L,
                ownerHash(),
                OWNER_TOKEN,
                payload(),
                status,
                7L,
                null,
                NOW,
                NOW.plusSeconds(600),
                result,
                errorCode
        );
    }

    private String ownerHash() {
        return new ReservationQueueIdentityHasher().ownerHash("0xowner");
    }

    private ReservationQueuePayload payload() {
        return ReservationQueuePayload.current(
                42L,
                new ReservationQueueIdentityHasher().idempotencyKey(validKey()),
                "c".repeat(64),
                List.of(1L, 3L)
        );
    }

    private ReservationQueueApiRequest request() {
        return new ReservationQueueApiRequest(42L, List.of(1L, 3L));
    }

    private String validKey() {
        return "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1";
    }
}

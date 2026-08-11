package org.example.ticket.reservation.queue.util.worker;

import org.example.ticket.reservation.common.factory.ReservationIntentFingerprintFactory;
import org.example.ticket.reservation.common.value.ReservationIdempotencyKey;
import org.example.ticket.reservation.queue.config.ReservationQueueWorkerProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueClaimResult;
import org.example.ticket.reservation.queue.dto.ReservationQueuePayload;
import org.example.ticket.reservation.queue.dto.ReservationQueueStreamMessage;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;
import org.example.ticket.reservation.queue.exception.ReservationQueuePayloadException;
import org.example.ticket.reservation.queue.repository.ReservationQueueExpiryIndex;
import org.example.ticket.reservation.queue.repository.ReservationQueueWorkerStore;
import org.example.ticket.reservation.queue.service.ReservationQueueWorkHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationQueueWorkerPollerTest {

    private final ReservationQueueWorkerStore store = mock(ReservationQueueWorkerStore.class);
    private final ReservationQueueExpiryIndex expiryIndex = mock(ReservationQueueExpiryIndex.class);
    private final ReservationQueueStreamPayloadDecoder decoder = mock(ReservationQueueStreamPayloadDecoder.class);
    private final ReservationQueueWorkHandler handler = mock(ReservationQueueWorkHandler.class);
    private final ReservationQueueWorkerPermits permits = new ReservationQueueWorkerPermits(1, 1);
    private final ReservationQueueWorkerProperties properties = new ReservationQueueWorkerProperties(
            true,
            "booking-workers",
            "worker-a",
            1,
            1,
            Duration.ofMillis(10),
            Duration.ofMillis(10)
    );
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-12T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        when(expiryIndex.activePerformanceTimeIds()).thenReturn(List.of(42L));
    }

    @Test
    void readsAndClaimsOnlyAfterPermitThenReturnsPermitAfterHandling() {
        ReservationQueueStreamMessage message = message();
        ReservationQueueWorkItem item = item();
        when(store.readNew(42L, "booking-workers", "worker-a", Duration.ofMillis(10)))
                .thenReturn(Optional.of(message));
        when(decoder.decode(message)).thenReturn(item);
        when(store.claim(item, "worker-a", clock.instant(), Duration.ofSeconds(30)))
                .thenReturn(ReservationQueueClaimResult.CLAIMED);
        ReservationQueueWorkerPoller poller = poller(Runnable::run);

        assertThat(poller.pollOnce()).isEqualTo(1);

        verify(store).ensureConsumerGroup(42L, "booking-workers");
        verify(handler).handle(item, "worker-a");
        assertThat(permits.availableGlobalPermits()).isEqualTo(1);
    }

    @Test
    void emptyStreamReturnsPermitWithoutClaim() {
        when(store.readNew(42L, "booking-workers", "worker-a", Duration.ofMillis(10)))
                .thenReturn(Optional.empty());

        assertThat(poller(Runnable::run).pollOnce()).isZero();

        assertThat(permits.availableGlobalPermits()).isEqualTo(1);
        verify(store, never()).claim(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectedSubmissionReturnsPermitAndLeavesClaimForRecovery() {
        ReservationQueueStreamMessage message = message();
        ReservationQueueWorkItem item = item();
        when(store.readNew(42L, "booking-workers", "worker-a", Duration.ofMillis(10)))
                .thenReturn(Optional.of(message));
        when(decoder.decode(message)).thenReturn(item);
        when(store.claim(item, "worker-a", clock.instant(), Duration.ofSeconds(30)))
                .thenReturn(ReservationQueueClaimResult.CLAIMED);
        Executor rejecting = command -> {
            throw new RejectedExecutionException("full");
        };

        assertThat(poller(rejecting).pollOnce()).isZero();

        assertThat(permits.availableGlobalPermits()).isEqualTo(1);
        verify(handler, never()).handle(item, "worker-a");
    }

    @Test
    void invalidPayloadUsesRejectHandlerWithoutRedisClaim() {
        ReservationQueueStreamMessage message = message();
        ReservationQueuePayloadException exception = new ReservationQueuePayloadException(
                ReservationQueuePayloadException.Reason.UNSUPPORTED_SCHEMA,
                "unsupported"
        );
        when(store.readNew(42L, "booking-workers", "worker-a", Duration.ofMillis(10)))
                .thenReturn(Optional.of(message));
        when(decoder.decode(message)).thenThrow(exception);

        assertThat(poller(Runnable::run).pollOnce()).isEqualTo(1);

        verify(handler).reject(message, "worker-a", exception);
        verify(store, never()).claim(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        assertThat(permits.availableGlobalPermits()).isEqualTo(1);
    }

    @Test
    void failedClaimReturnsPermitWithoutDispatch() {
        ReservationQueueStreamMessage message = message();
        ReservationQueueWorkItem item = item();
        when(store.readNew(42L, "booking-workers", "worker-a", Duration.ofMillis(10)))
                .thenReturn(Optional.of(message));
        when(decoder.decode(message)).thenReturn(item);
        when(store.claim(item, "worker-a", clock.instant(), Duration.ofSeconds(30)))
                .thenReturn(ReservationQueueClaimResult.NOT_WAITING);

        assertThat(poller(Runnable::run).pollOnce()).isZero();

        verify(handler, never()).handle(item, "worker-a");
        assertThat(permits.availableGlobalPermits()).isEqualTo(1);
    }

    private ReservationQueueWorkerPoller poller(Executor executor) {
        return new ReservationQueueWorkerPoller(
                store,
                expiryIndex,
                decoder,
                permits,
                handler,
                executor,
                properties,
                Duration.ofSeconds(30),
                clock
        );
    }

    private ReservationQueueStreamMessage message() {
        return new ReservationQueueStreamMessage(
                42L,
                "1786356000000-0",
                Map.of("ticketId", "f76f5ac8-a475-4e04-906a-1f54765f9770")
        );
    }

    private ReservationQueueWorkItem item() {
        String hash = ReservationIntentFingerprintFactory.create(42L, List.of(1L)).requestHash();
        return new ReservationQueueWorkItem(
                UUID.fromString("f76f5ac8-a475-4e04-906a-1f54765f9770"),
                42L,
                "1786356000000-0",
                "b".repeat(64),
                UUID.fromString("da64524f-ac82-45a8-9d38-4cd641b72343"),
                ReservationQueuePayload.current(
                        7L,
                        ReservationIdempotencyKey.from("a0ebc4c9-8d82-47af-8127-1fc3d27e47a1"),
                        hash,
                        List.of(1L)
                ),
                9L,
                Instant.parse("2026-08-12T09:59:00Z")
        );
    }
}

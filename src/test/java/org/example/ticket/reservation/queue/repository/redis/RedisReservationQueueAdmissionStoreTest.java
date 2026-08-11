package org.example.ticket.reservation.queue.repository.redis;

import org.example.ticket.reservation.queue.dto.ReservationQueueAdmissionCommand;
import org.example.ticket.reservation.queue.dto.ReservationQueueAdmissionResult;
import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueuePayload;
import org.example.ticket.reservation.common.value.ReservationIdempotencyKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.mockito.stubbing.OngoingStubbing;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisReservationQueueAdmissionStoreTest {

    private static final UUID TICKET_ID = UUID.fromString("f76f5ac8-a475-4e04-906a-1f54765f9770");
    private static final UUID OWNER_TOKEN = UUID.fromString("da64524f-ac82-45a8-9d38-4cd641b72343");

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);

    private RedisReservationQueueAdmissionStore store;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        store = new RedisReservationQueueAdmissionStore(
                redisTemplate,
                ReservationQueueProperties.defaults(),
                new ReservationQueueKeyFactory(),
                () -> OWNER_TOKEN
        );
    }

    @Test
    void reservesIdempotencyThenEnqueuesAndMarksMappingQueued() {
        scriptResults("CREATED", "ACCEPTED|7|1731204000000-0", "MARKED");

        ReservationQueueAdmissionResult result = store.admit(command());

        assertThat(result.outcome()).isEqualTo(ReservationQueueAdmissionResult.Outcome.ACCEPTED);
        assertThat(result.ticketId()).isEqualTo(TICKET_ID);
        assertThat(result.performanceTimeId()).isEqualTo(42L);
        assertThat(result.sequence()).isEqualTo(7L);
        assertThat(result.streamId()).isEqualTo("1731204000000-0");
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate, times(3)).execute(anyScript(), anyList(), arguments.capture());
        Object[] enqueueArguments = arguments.getAllValues().get(1);
        assertThat(enqueueArguments[3]).isEqualTo(OWNER_TOKEN.toString());
        assertThat(enqueueArguments[4]).isEqualTo("1");
        assertThat(enqueueArguments[5]).isEqualTo("42");
        assertThat(enqueueArguments[6]).isEqualTo("a0ebc4c9-8d82-47af-8127-1fc3d27e47a1");
        assertThat(enqueueArguments[7]).isEqualTo(ReservationIdempotencyKey.from(
                "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1"
        ).hash());
        verify(zSetOperations).add(
                "reservation:queue:active-performance-times",
                "42",
                Instant.parse("2026-08-10T10:30:00Z").toEpochMilli()
        );
    }

    @Test
    void returnsExistingTicketForSameQueuedRequest() {
        scriptResults("EXISTING|QUEUED|" + TICKET_ID + "|42");

        ReservationQueueAdmissionResult result = store.admit(command());

        assertThat(result.outcome()).isEqualTo(ReservationQueueAdmissionResult.Outcome.EXISTING);
        assertThat(result.ticketId()).isEqualTo(TICKET_ID);
        verify(redisTemplate, times(1)).execute(anyScript(), anyList(), any(Object[].class));
        verify(zSetOperations, never()).add(any(), any(), any(Double.class));
    }

    @Test
    void reportsInProgressAndConflictWithoutCreatingAnotherTicket() {
        scriptResults("EXISTING|ENQUEUING|" + TICKET_ID + "|42");
        assertThat(store.admit(command()).outcome())
                .isEqualTo(ReservationQueueAdmissionResult.Outcome.ENQUEUE_IN_PROGRESS);

        scriptResults("CONFLICT");
        assertThat(store.admit(command()).outcome())
                .isEqualTo(ReservationQueueAdmissionResult.Outcome.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void releasesOwnedIdempotencyReservationWhenQueueIsFull() {
        scriptResults("CREATED", "FULL", "RELEASED");

        ReservationQueueAdmissionResult result = store.admit(command());

        assertThat(result.outcome()).isEqualTo(ReservationQueueAdmissionResult.Outcome.QUEUE_FULL);
        verify(redisTemplate, times(3)).execute(anyScript(), anyList(), any(Object[].class));
    }

    @Test
    void keepsIdempotencyReservationWhenEnqueueOutcomeIsAmbiguous() {
        when(redisTemplate.execute(anyScript(), anyList(), any(Object[].class)))
                .thenReturn("CREATED")
                .thenThrow(new QueryTimeoutException("enqueue timed out"));

        assertThatThrownBy(() -> store.admit(command()))
                .isInstanceOf(QueryTimeoutException.class);

        verify(redisTemplate, times(2)).execute(anyScript(), anyList(), any(Object[].class));
    }

    @Test
    void keepsRecoverableTicketWhenQueuedMarkFails() {
        scriptResults("CREATED", "ACCEPTED|7|1731204000000-0", "OWNER_MISMATCH");

        assertThatThrownBy(() -> store.admit(command()))
                .isInstanceOf(org.example.ticket.reservation.queue.exception.ReservationQueueStorageException.class)
                .hasMessageContaining("not marked");

        verify(redisTemplate, times(3)).execute(anyScript(), anyList(), any(Object[].class));
        verify(zSetOperations, never()).add(any(), any(), any(Double.class));
    }

    @Test
    void admissionSucceedsEvenIfActiveRegistryRefreshFails() {
        scriptResults("CREATED", "ACCEPTED|1|1731204000000-0", "MARKED");
        when(zSetOperations.add(any(), any(), any(Double.class)))
                .thenThrow(new QueryTimeoutException("registry update timed out"));

        assertThat(store.admit(command()).outcome())
                .isEqualTo(ReservationQueueAdmissionResult.Outcome.ACCEPTED);
    }

    private void scriptResults(String... results) {
        OngoingStubbing<String> stubbing = when(
                redisTemplate.execute(anyScript(), anyList(), any(Object[].class))
        );
        for (String result : results) {
            stubbing = stubbing.thenReturn(result);
        }
    }

    private RedisScript<String> anyScript() {
        return org.mockito.ArgumentMatchers.any();
    }

    private ReservationQueueAdmissionCommand command() {
        return new ReservationQueueAdmissionCommand(
                42L,
                TICKET_ID,
                "a".repeat(64),
                ReservationQueuePayload.current(
                        42L,
                        ReservationIdempotencyKey.from("a0ebc4c9-8d82-47af-8127-1fc3d27e47a1"),
                        "c".repeat(64),
                        List.of(1L, 3L)
                ),
                Instant.parse("2026-08-10T10:00:00Z")
        );
    }
}

package org.example.ticket.reservation.queue.infrastructure.redis;

import org.example.ticket.reservation.queue.application.ReservationQueueAdmissionCommand;
import org.example.ticket.reservation.queue.application.ReservationQueueAdmissionResult;
import org.example.ticket.reservation.queue.application.ReservationQueueProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.mockito.stubbing.OngoingStubbing;

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
        verify(redisTemplate, times(3)).execute(anyScript(), anyList(), any(Object[].class));
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
                "b".repeat(64),
                "c".repeat(64),
                List.of(1L, 3L),
                Instant.parse("2026-08-10T10:00:00Z")
        );
    }
}

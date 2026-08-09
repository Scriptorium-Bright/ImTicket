package org.example.ticket.reservation.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisReservationQueueTicketStoreTest {

    private static final UUID TICKET_ID = UUID.fromString("f76f5ac8-a475-4e04-906a-1f54765f9770");

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);

    private RedisReservationQueueTicketStore store;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        store = new RedisReservationQueueTicketStore(
                redisTemplate,
                ReservationQueueProperties.defaults(),
                new ReservationQueueKeyFactory()
        );
    }

    @Test
    void readsTicketHashAndOneBasedWaitingPosition() {
        when(hashOperations.entries(ticketKey())).thenReturn(waitingFields());
        when(zSetOperations.rank("reservation:queue:{42}:waiting", TICKET_ID.toString())).thenReturn(4L);

        Optional<ReservationQueueTicketSnapshot> result = store.find(42L, TICKET_ID);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().status()).isEqualTo(ReservationQueueStatus.WAITING);
        assertThat(result.orElseThrow().position()).isEqualTo(5L);
        assertThat(result.orElseThrow().deadlineAt())
                .isEqualTo(Instant.parse("2026-08-10T10:10:00Z"));
    }

    @Test
    void missingHashReturnsEmptyTicket() {
        when(hashOperations.entries(ticketKey())).thenReturn(Map.of());

        assertThat(store.find(42L, TICKET_ID)).isEmpty();
    }

    @Test
    void expireScriptReportsOnlyAppliedTransition() {
        when(redisTemplate.execute(anyScript(), anyList(), any(Object[].class)))
                .thenReturn("EXPIRED")
                .thenReturn("NOT_DUE");

        assertThat(store.expireIfDue(42L, TICKET_ID, Instant.parse("2026-08-10T10:10:00Z")))
                .isTrue();
        assertThat(store.expireIfDue(42L, TICKET_ID, Instant.parse("2026-08-10T10:09:59Z")))
                .isFalse();
    }

    private Map<Object, Object> waitingFields() {
        Map<Object, Object> fields = new LinkedHashMap<>();
        fields.put("ticketId", TICKET_ID.toString());
        fields.put("performanceTimeId", "42");
        fields.put("ownerHash", "a".repeat(64));
        fields.put("status", "WAITING");
        fields.put("sequence", "7");
        fields.put("enqueuedAt", "1786356000000");
        fields.put("deadlineAt", "1786356600000");
        return fields;
    }

    private String ticketKey() {
        return "reservation:queue:{42}:ticket:" + TICKET_ID;
    }

    private RedisScript<String> anyScript() {
        return org.mockito.ArgumentMatchers.any();
    }
}

package org.example.ticket.reservation.waitingroom.repository.redis;

import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffRequest;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffState;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffSubmission;
import org.example.ticket.reservation.waitingroom.repository.WaitingRoomJoinHandoffStreamRecord;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomJoinHandoffStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "WAITING_ROOM_REDIS_TEST_ENABLED", matches = "(?i)true")
class RedisWaitingRoomJoinHandoffStoreIntegrationTest {

    private static final long PERFORMANCE_TIME_ID = 7101L;
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private RedisWaitingRoomJoinHandoffStore store;

    @BeforeAll
    static void connectRedis() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                environment("WAITING_ROOM_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(environment("WAITING_ROOM_REDIS_PORT", "16380"))
        );
        configuration.setDatabase(Integer.parseInt(environment("WAITING_ROOM_REDIS_DATABASE", "15")));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @BeforeEach
    void resetRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        store = new RedisWaitingRoomJoinHandoffStore(redisTemplate, new WaitingRoomKeyFactory());
    }

    @AfterAll
    static void closeRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void convergesDuplicateOwnerAndRecoversPendingStreamEntry() {
        UUID firstRequestId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID duplicateRequestId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID firstTicketId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID duplicateTicketId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        WaitingRoomJoinHandoffRequest first = new WaitingRoomJoinHandoffRequest(
                firstRequestId, firstTicketId, PERFORMANCE_TIME_ID, 42L, NOW, NOW.plus(Duration.ofHours(1))
        );
        WaitingRoomJoinHandoffRequest duplicate = new WaitingRoomJoinHandoffRequest(
                duplicateRequestId, duplicateTicketId, PERFORMANCE_TIME_ID, 42L,
                NOW.plusMillis(1), NOW.plus(Duration.ofHours(1))
        );

        WaitingRoomJoinHandoffSubmission created = store.enqueue(first, Duration.ofHours(1), 10);
        WaitingRoomJoinHandoffSubmission existing = store.enqueue(duplicate, Duration.ofHours(1), 10);

        assertThat(created).isEqualTo(new WaitingRoomJoinHandoffSubmission(firstRequestId, firstTicketId, true));
        assertThat(existing).isEqualTo(new WaitingRoomJoinHandoffSubmission(firstRequestId, false));
        assertThat(store.find(PERFORMANCE_TIME_ID, firstRequestId)).get()
                .extracting(WaitingRoomJoinHandoffState::status)
                .isEqualTo(WaitingRoomJoinHandoffStatus.QUEUED);

        store.ensureConsumerGroup(PERFORMANCE_TIME_ID);
        store.ensureConsumerGroup(PERFORMANCE_TIME_ID);
        Optional<WaitingRoomJoinHandoffStreamRecord> read = store.readNext(PERFORMANCE_TIME_ID);
        assertThat(read).isPresent();
        assertThat(read.orElseThrow().request().requestId()).isEqualTo(firstRequestId);

        Optional<WaitingRoomJoinHandoffStreamRecord> recovered = store.claimIdle(
                PERFORMANCE_TIME_ID,
                Duration.ZERO
        );
        assertThat(recovered).isPresent();
        assertThat(recovered.orElseThrow().request().requestId()).isEqualTo(firstRequestId);

        store.markProcessing(PERFORMANCE_TIME_ID, firstRequestId, Duration.ofHours(1));
        store.markCompleted(
                PERFORMANCE_TIME_ID,
                firstRequestId,
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                Duration.ofHours(1)
        );
        store.acknowledge(PERFORMANCE_TIME_ID, recovered.orElseThrow().streamRecordId());

        assertThat(store.find(PERFORMANCE_TIME_ID, firstRequestId)).get()
                .extracting(WaitingRoomJoinHandoffState::status)
                .isEqualTo(WaitingRoomJoinHandoffStatus.COMPLETED);
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

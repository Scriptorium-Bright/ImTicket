package org.example.ticket.reservation.waitingroom.repository.redis;

import org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinResult;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomTicketSnapshot;
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
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "WAITING_ROOM_REDIS_TEST_ENABLED", matches = "(?i)true")
class RedisWaitingRoomStoreIntegrationTest {

    private static final long PERFORMANCE_TIME_ID = 7001L;
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private RedisWaitingRoomStore store;

    /** 환경 변수로 지정한 Redis에 연결해 integration test fixture를 구성한다. */
    @BeforeAll
    static void connectRedis() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                environment("WAITING_ROOM_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(environment("WAITING_ROOM_REDIS_PORT", "16380"))
        );
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    /** 각 test가 회차 key를 공유하지 않도록 Redis database를 비운다. */
    @BeforeEach
    void resetRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        store = new RedisWaitingRoomStore(redisTemplate, new WaitingRoomKeyFactory());
    }

    /** 테스트 종료 시 Redis connection factory를 닫는다. */
    @AfterAll
    static void closeRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    /** 동일 회원 join dedupe, sequence 순서, active 상한을 함께 검증한다. */
    @Test
    void joinsPromotesAndCompletesWithinActiveCapacity() {
        UUID first = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID second = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Duration retention = Duration.ofHours(1);

        WaitingRoomJoinResult firstJoin = store.join(
                PERFORMANCE_TIME_ID, 11L, first, NOW, NOW.plus(Duration.ofMinutes(30)), retention
        );
        WaitingRoomJoinResult duplicateJoin = store.join(
                PERFORMANCE_TIME_ID, 11L, second, NOW, NOW.plus(Duration.ofMinutes(30)), retention
        );
        store.join(PERFORMANCE_TIME_ID, 12L, second, NOW, NOW.plus(Duration.ofMinutes(30)), retention);

        assertThat(firstJoin.created()).isTrue();
        assertThat(firstJoin.sequence()).isEqualTo(1L);
        assertThat(duplicateJoin.created()).isFalse();
        assertThat(duplicateJoin.ticketId()).isEqualTo(first);
        assertThat(store.waitingRank(PERFORMANCE_TIME_ID, first)).hasValue(0L);
        assertThat(store.waitingRank(PERFORMANCE_TIME_ID, second)).hasValue(1L);

        assertThat(store.promote(
                PERFORMANCE_TIME_ID, NOW, Duration.ofMinutes(5), 1, 2, retention
        )).extracting(WaitingRoomTicketSnapshot::ticketId).containsExactly(first);
        assertThat(store.promote(
                PERFORMANCE_TIME_ID, NOW, Duration.ofMinutes(5), 1, 2, retention
        )).isEmpty();

        assertThat(store.find(PERFORMANCE_TIME_ID, first)).get()
                .extracting(WaitingRoomTicketSnapshot::status)
                .isEqualTo(WaitingRoomTicketStatus.ADMITTED);
        assertThat(store.complete(PERFORMANCE_TIME_ID, 11L, first, NOW, retention)).get()
                .extracting(WaitingRoomTicketSnapshot::status)
                .isEqualTo(WaitingRoomTicketStatus.COMPLETED);
        assertThat(store.promote(
                PERFORMANCE_TIME_ID, NOW, Duration.ofMinutes(5), 1, 2, retention
        )).extracting(WaitingRoomTicketSnapshot::ticketId).containsExactly(second);
    }

    /** waiting deadline과 admitted lease가 due scan에서 EXPIRED로 정리되는지 검증한다. */
    @Test
    void expiresWaitingTicketWhenDeadlineIsDue() {
        UUID ticketId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Duration retention = Duration.ofHours(1);
        store.join(PERFORMANCE_TIME_ID, 13L, ticketId, NOW, NOW.plusSeconds(1), retention);

        store.promote(
                PERFORMANCE_TIME_ID,
                NOW.plusSeconds(2),
                Duration.ofMinutes(5),
                10,
                10,
                retention
        );

        assertThat(store.find(PERFORMANCE_TIME_ID, ticketId)).get()
                .extracting(WaitingRoomTicketSnapshot::status)
                .isEqualTo(WaitingRoomTicketStatus.EXPIRED);
        OptionalLong rank = store.waitingRank(PERFORMANCE_TIME_ID, ticketId);
        assertThat(rank).isEmpty();
    }

    /** Redis integration test에 사용할 환경 변수를 읽고 기본 host·port를 적용한다. */
    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

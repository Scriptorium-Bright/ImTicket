package org.example.ticket.reservation.waitingroom.repository.redis;

import org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinResult;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomTicketSnapshot;
import org.example.ticket.reservation.waitingroom.exception.WaitingRoomCapacityException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
                PERFORMANCE_TIME_ID, 11L, first, NOW, NOW.plus(Duration.ofMinutes(30)), retention, 10
        );
        WaitingRoomJoinResult duplicateJoin = store.join(
                PERFORMANCE_TIME_ID, 11L, second, NOW, NOW.plus(Duration.ofMinutes(30)), retention, 10
        );
        store.join(PERFORMANCE_TIME_ID, 12L, second, NOW, NOW.plus(Duration.ofMinutes(30)), retention, 10);

        assertThat(firstJoin.created()).isTrue();
        assertThat(firstJoin.sequence()).isEqualTo(1L);
        assertThat(duplicateJoin.created()).isFalse();
        assertThat(duplicateJoin.ticketId()).isEqualTo(first);
        assertThat(store.waitingRank(PERFORMANCE_TIME_ID, first)).hasValue(0L);
        assertThat(store.waitingRank(PERFORMANCE_TIME_ID, second)).hasValue(1L);

        assertThat(store.promote(
                PERFORMANCE_TIME_ID, NOW, Duration.ofMinutes(5), 1, 2, Duration.ofSeconds(1), retention
        )).extracting(WaitingRoomTicketSnapshot::ticketId).containsExactly(first);
        assertThat(store.promote(
                PERFORMANCE_TIME_ID, NOW, Duration.ofMinutes(5), 1, 2, Duration.ofSeconds(1), retention
        )).isEmpty();

        assertThat(store.find(PERFORMANCE_TIME_ID, first)).get()
                .extracting(WaitingRoomTicketSnapshot::status)
                .isEqualTo(WaitingRoomTicketStatus.ADMITTED);
        assertThat(store.complete(PERFORMANCE_TIME_ID, 11L, first, NOW, retention)).get()
                .extracting(WaitingRoomTicketSnapshot::status)
                .isEqualTo(WaitingRoomTicketStatus.COMPLETED);
        assertThat(store.promote(
                PERFORMANCE_TIME_ID, NOW, Duration.ofMinutes(5), 1, 2, Duration.ofSeconds(1), retention
        )).extracting(WaitingRoomTicketSnapshot::ticketId).containsExactly(second);
    }

    /** waiting deadline과 admitted lease가 due scan에서 EXPIRED로 정리되는지 검증한다. */
    @Test
    void expiresWaitingTicketWhenDeadlineIsDue() {
        UUID ticketId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Duration retention = Duration.ofHours(1);
        store.join(PERFORMANCE_TIME_ID, 13L, ticketId, NOW, NOW.plusSeconds(1), retention, 10);

        store.promote(
                PERFORMANCE_TIME_ID,
                NOW.plusSeconds(2),
                Duration.ofMinutes(5),
                10,
                10,
                Duration.ofSeconds(1),
                retention
        );

        assertThat(store.find(PERFORMANCE_TIME_ID, ticketId)).get()
                .extracting(WaitingRoomTicketSnapshot::status)
                .isEqualTo(WaitingRoomTicketStatus.EXPIRED);
        OptionalLong rank = store.waitingRank(PERFORMANCE_TIME_ID, ticketId);
        assertThat(rank).isEmpty();
    }

    /** 만료 scan batch보다 많은 due ticket이 다음 후보로 admitted되지 않는지 검증한다. */
    @Test
    void doesNotPromoteExpiredCandidateAfterExpiryBatchIsExhausted() {
        UUID first = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID second = UUID.fromString("55555555-5555-5555-5555-555555555555");
        Duration retention = Duration.ofHours(1);
        Instant deadline = NOW.plusSeconds(1);

        store.join(PERFORMANCE_TIME_ID, 21L, first, NOW, deadline, retention, 10);
        store.join(PERFORMANCE_TIME_ID, 22L, second, NOW, deadline, retention, 10);

        store.promote(
                PERFORMANCE_TIME_ID,
                NOW.plusSeconds(2),
                Duration.ofMinutes(5),
                10,
                1,
                Duration.ofSeconds(1),
                retention
        );

        assertThat(store.find(PERFORMANCE_TIME_ID, first)).get()
                .extracting(WaitingRoomTicketSnapshot::status)
                .isEqualTo(WaitingRoomTicketStatus.EXPIRED);
        assertThat(store.find(PERFORMANCE_TIME_ID, second)).get()
                .extracting(WaitingRoomTicketSnapshot::status)
                .isEqualTo(WaitingRoomTicketStatus.EXPIRED);
    }

    /** 서로 다른 application instance가 같은 회차 interval quota를 공유하는지 검증한다. */
    @Test
    void enforcesAdmissionQuotaAcrossStoreInstancesWithinWindow() {
        RedisWaitingRoomStore secondStore = new RedisWaitingRoomStore(
                redisTemplate,
                new WaitingRoomKeyFactory()
        );
        Duration retention = Duration.ofHours(1);
        UUID first = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID second = UUID.fromString("77777777-7777-7777-7777-777777777777");

        store.join(PERFORMANCE_TIME_ID, 31L, first, NOW, NOW.plus(Duration.ofMinutes(30)), retention, 10);
        store.join(PERFORMANCE_TIME_ID, 32L, second, NOW, NOW.plus(Duration.ofMinutes(30)), retention, 10);

        assertThat(store.promote(
                PERFORMANCE_TIME_ID,
                NOW,
                Duration.ofMinutes(5),
                10,
                1,
                Duration.ofSeconds(1),
                retention
        )).hasSize(1);
        assertThat(secondStore.promote(
                PERFORMANCE_TIME_ID,
                NOW,
                Duration.ofMinutes(5),
                10,
                1,
                Duration.ofSeconds(1),
                retention
        )).isEmpty();

        assertThat(store.find(PERFORMANCE_TIME_ID, second)).get()
                .extracting(WaitingRoomTicketSnapshot::status)
                .isEqualTo(WaitingRoomTicketStatus.WAITING);
        assertThat(secondStore.promote(
                PERFORMANCE_TIME_ID,
                NOW.plusSeconds(1),
                Duration.ofMinutes(5),
                10,
                1,
                Duration.ofSeconds(1),
                retention
        )).extracting(WaitingRoomTicketSnapshot::ticketId).containsExactly(second);
    }

    /** queue capacity 초과가 새 ticket을 만들지 않고 명시적 예외를 반환하는지 검증한다. */
    @Test
    void rejectsJoinWhenWaitingCapacityIsFull() {
        Duration retention = Duration.ofHours(1);
        UUID first = UUID.fromString("88888888-8888-8888-8888-888888888888");
        UUID second = UUID.fromString("99999999-9999-9999-9999-999999999999");

        store.join(PERFORMANCE_TIME_ID, 41L, first, NOW, NOW.plus(Duration.ofMinutes(30)), retention, 1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.join(
                PERFORMANCE_TIME_ID, 42L, second, NOW, NOW.plus(Duration.ofMinutes(30)), retention, 1
        )).isInstanceOf(WaitingRoomCapacityException.class);

        assertThat(redisTemplate.opsForZSet().rank(
                new WaitingRoomKeyFactory().waiting(PERFORMANCE_TIME_ID), second.toString()
        )).isNull();
        assertThat(store.find(PERFORMANCE_TIME_ID, second)).isEmpty();
    }

    /** 같은 owner의 동시 join이 하나의 ticket mapping으로 수렴하는지 검증한다. */
    @Test
    void convergesConcurrentJoinRequestsForSameOwner() throws Exception {
        int requestCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WaitingRoomJoinResult>> futures = new ArrayList<>();
        Duration retention = Duration.ofHours(1);

        try {
            for (int index = 0; index < requestCount; index++) {
                UUID ticketId = UUID.nameUUIDFromBytes(("ticket-" + index).getBytes());
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return store.join(
                            PERFORMANCE_TIME_ID,
                            51L,
                            ticketId,
                            NOW,
                            NOW.plus(Duration.ofMinutes(30)),
                            retention,
                            10
                    );
                }));
            }
            ready.await();
            start.countDown();

            List<WaitingRoomJoinResult> results = new ArrayList<>();
            for (Future<WaitingRoomJoinResult> future : futures) {
                results.add(future.get());
            }

            UUID convergedTicketId = results.get(0).ticketId();
            assertThat(results).extracting(WaitingRoomJoinResult::ticketId).containsOnly(convergedTicketId);
            assertThat(redisTemplate.opsForZSet().zCard(
                    new WaitingRoomKeyFactory().waiting(PERFORMANCE_TIME_ID)
            )).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Redis integration test에 사용할 환경 변수를 읽고 기본 host·port를 적용한다. */
    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

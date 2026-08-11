package org.example.ticket.reservation.queue.repository.redis;

import org.example.ticket.reservation.common.factory.ReservationIntentFingerprintFactory;
import org.example.ticket.reservation.common.value.ReservationIdempotencyKey;
import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueAdmissionCommand;
import org.example.ticket.reservation.queue.dto.ReservationQueueClaimResult;
import org.example.ticket.reservation.queue.dto.ReservationQueuePayload;
import org.example.ticket.reservation.queue.dto.ReservationQueueStreamMessage;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;
import org.example.ticket.reservation.queue.util.worker.ReservationQueuePayloadV1Decoder;
import org.example.ticket.reservation.queue.util.worker.ReservationQueueStreamPayloadDecoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RESERVATION_QUEUE_REDIS_INTEGRATION", matches = "true")
class RedisReservationQueueWorkerStoreIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private ReservationQueueKeyFactory keyFactory;
    private RedisReservationQueueWorkerStore workerStore;

    @BeforeEach
    void setUp() {
        int port = Integer.parseInt(System.getenv().getOrDefault("RESERVATION_QUEUE_REDIS_PORT", "6389"));
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", port);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        keyFactory = new ReservationQueueKeyFactory();
        workerStore = new RedisReservationQueueWorkerStore(
                redisTemplate,
                ReservationQueueProperties.defaults(),
                keyFactory
        );
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void consumerGroupConvergesAndOnlyOneWorkerClaimsSameTicket() throws Exception {
        UUID ticketId = UUID.fromString("f76f5ac8-a475-4e04-906a-1f54765f9770");
        ReservationIdempotencyKey idempotencyKey = ReservationIdempotencyKey.from(
                "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1"
        );
        String requestHash = ReservationIntentFingerprintFactory.create(42L, List.of(1L, 3L)).requestHash();
        ReservationQueuePayload payload = ReservationQueuePayload.current(
                7L,
                idempotencyKey,
                requestHash,
                List.of(1L, 3L)
        );
        RedisReservationQueueAdmissionStore admissionStore = new RedisReservationQueueAdmissionStore(
                redisTemplate,
                ReservationQueueProperties.defaults(),
                keyFactory,
                () -> UUID.fromString("da64524f-ac82-45a8-9d38-4cd641b72343")
        );
        admissionStore.admit(new ReservationQueueAdmissionCommand(
                42L,
                ticketId,
                "b".repeat(64),
                payload,
                Instant.parse("2026-08-12T10:00:00Z")
        ));

        workerStore.ensureConsumerGroup(42L, "booking-workers");
        workerStore.ensureConsumerGroup(42L, "booking-workers");
        ReservationQueueStreamMessage message = workerStore.readNew(
                42L,
                "booking-workers",
                "reader",
                Duration.ofMillis(100)
        ).orElseThrow();
        ReservationQueueWorkItem item = new ReservationQueueStreamPayloadDecoder(
                List.of(new ReservationQueuePayloadV1Decoder())
        ).decode(message);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<ReservationQueueClaimResult> first = executor.submit(
                    () -> claimAfterBarrier(item, "worker-a", ready, start)
            );
            Future<ReservationQueueClaimResult> second = executor.submit(
                    () -> claimAfterBarrier(item, "worker-b", ready, start)
            );
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(
                            ReservationQueueClaimResult.CLAIMED,
                            ReservationQueueClaimResult.NOT_WAITING
                    );
        } finally {
            executor.shutdownNow();
        }

        String ticketKey = keyFactory.ticket(42L, ticketId);
        assertThat(redisTemplate.opsForHash().get(ticketKey, "status")).isEqualTo("PROCESSING");
        assertThat(redisTemplate.opsForHash().get(ticketKey, "workerId"))
                .isIn("worker-a", "worker-b");
        assertThat(redisTemplate.opsForZSet().score(keyFactory.processing(42L), ticketId.toString()))
                .isNotNull();
        assertThat(redisTemplate.opsForZSet().score(keyFactory.waiting(42L), ticketId.toString()))
                .isNull();
    }

    private ReservationQueueClaimResult claimAfterBarrier(
            ReservationQueueWorkItem item,
            String workerId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return workerStore.claim(
                item,
                workerId,
                Instant.parse("2026-08-12T10:00:01Z"),
                Duration.ofSeconds(30)
        );
    }
}

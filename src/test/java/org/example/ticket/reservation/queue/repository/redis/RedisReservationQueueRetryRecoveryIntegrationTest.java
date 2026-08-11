package org.example.ticket.reservation.queue.repository.redis;

import org.example.ticket.reservation.common.factory.ReservationIntentFingerprintFactory;
import org.example.ticket.reservation.common.value.ReservationIdempotencyKey;
import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.config.ReservationQueueRetryProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueAdmissionCommand;
import org.example.ticket.reservation.queue.dto.ReservationQueueClaimResult;
import org.example.ticket.reservation.queue.dto.ReservationQueuePayload;
import org.example.ticket.reservation.queue.dto.ReservationQueueRetryResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueStreamMessage;
import org.example.ticket.reservation.queue.dto.ReservationQueueSuccessResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueTerminalResult;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RESERVATION_QUEUE_REDIS_INTEGRATION", matches = "true")
class RedisReservationQueueRetryRecoveryIntegrationTest {

    private static final long PERFORMANCE = 42L;
    private static final UUID TICKET = UUID.fromString("f76f5ac8-a475-4e04-906a-1f54765f9770");
    private static final UUID OWNER = UUID.fromString("da64524f-ac82-45a8-9d38-4cd641b72343");
    private static final String GROUP = "booking-workers";
    private static final Instant START = Instant.parse("2026-08-12T10:00:00Z");

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private ReservationQueueKeyFactory keyFactory;
    private ReservationQueueProperties queueProperties;
    private RedisReservationQueueWorkerStore workerStore;
    private RedisReservationQueueRetryStore retryStore;

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
        queueProperties = ReservationQueueProperties.defaults();
        workerStore = new RedisReservationQueueWorkerStore(redisTemplate, queueProperties, keyFactory);
        retryStore = new RedisReservationQueueRetryStore(
                redisTemplate,
                queueProperties,
                new ReservationQueueRetryProperties(
                        3, Duration.ofMillis(10), Duration.ofMillis(40), 10
                ),
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
    void retryIsPromotedWithoutSleepingAndBudgetExhaustionBecomesFinal() {
        ReservationQueueWorkItem first = enqueueReadAndClaim("worker-a", START);

        assertThat(retryStore.schedule(
                first, "worker-a", "SEAT_ADMISSION_REJECTED", START.plusMillis(1)
        )).isEqualTo(ReservationQueueRetryResult.SCHEDULED);
        assertThat(workerStore.acknowledge(first, GROUP)).isEqualTo(1L);
        assertThat(retryStore.promoteDue(PERFORMANCE, START.plusMillis(10), 10)).isZero();
        assertThat(retryStore.promoteDue(PERFORMANCE, START.plusMillis(11), 10)).isEqualTo(1);

        ReservationQueueWorkItem second = readAndClaim("worker-a", START.plusMillis(12));
        assertThat(retryStore.schedule(
                second, "worker-a", "SEAT_LOCK_TIMEOUT", START.plusMillis(13)
        )).isEqualTo(ReservationQueueRetryResult.SCHEDULED);
        assertThat(workerStore.acknowledge(second, GROUP)).isEqualTo(1L);
        assertThat(retryStore.promoteDue(PERFORMANCE, START.plusMillis(33), 10)).isEqualTo(1);

        ReservationQueueWorkItem third = readAndClaim("worker-a", START.plusMillis(34));
        assertThat(retryStore.schedule(
                third, "worker-a", "SEAT_LOCK_TIMEOUT", START.plusMillis(35)
        )).isEqualTo(ReservationQueueRetryResult.EXHAUSTED);
        assertThat(workerStore.acknowledge(third, GROUP)).isEqualTo(1L);
        assertThat(redisTemplate.opsForHash().get(
                keyFactory.ticket(PERFORMANCE, TICKET), "status"
        )).isEqualTo("FAILED_FINAL");
        assertThat(redisTemplate.opsForHash().get(
                keyFactory.ticket(PERFORMANCE, TICKET), "errorCode"
        )).isEqualTo("QUEUE_RETRY_EXHAUSTED");
    }

    @Test
    void expiredProcessingLeaseTransfersPendingOwnershipToAnotherWorker() {
        ReservationQueueWorkItem original = enqueueReadAndClaim("worker-a", START);
        ReservationQueueStreamMessage stale = workerStore.readStaleCandidate(
                PERFORMANCE, GROUP, Duration.ZERO
        ).orElseThrow();
        ReservationQueueWorkItem recovered = decoder().decode(stale);

        assertThat(workerStore.recover(
                recovered,
                GROUP,
                "worker-b",
                START.plusSeconds(29),
                Duration.ofSeconds(30)
        )).isEqualTo(ReservationQueueClaimResult.LEASE_ACTIVE);
        assertThat(workerStore.recover(
                recovered,
                GROUP,
                "worker-b",
                START.plusSeconds(31),
                Duration.ofSeconds(30)
        )).isEqualTo(ReservationQueueClaimResult.RECOVERED);
        assertThat(redisTemplate.opsForHash().get(
                keyFactory.ticket(PERFORMANCE, TICKET), "workerId"
        )).isEqualTo("worker-b");
        assertThat(original.streamId()).isEqualTo(recovered.streamId());
    }

    @Test
    void terminalTicketWithUnackedEntryIsAckedWithoutDatabaseReplay() {
        ReservationQueueWorkItem item = enqueueReadAndClaim("worker-a", START);
        RedisReservationQueueTerminalStore terminalStore = new RedisReservationQueueTerminalStore(
                redisTemplate, queueProperties, keyFactory
        );
        assertThat(terminalStore.completeSuccess(
                item,
                "worker-a",
                new ReservationQueueSuccessResult(
                        1, 20L, 10_000, "reservation-20",
                        LocalDateTime.parse("2026-08-12T10:07:00")
                ),
                START.plusSeconds(1)
        )).isEqualTo(ReservationQueueTerminalResult.COMPLETED);

        ReservationQueueStreamMessage stale = workerStore.readStaleCandidate(
                PERFORMANCE, GROUP, Duration.ZERO
        ).orElseThrow();
        ReservationQueueWorkItem recovered = decoder().decode(stale);
        assertThat(workerStore.recover(
                recovered,
                GROUP,
                "worker-b",
                START.plusSeconds(31),
                Duration.ofSeconds(30)
        )).isEqualTo(ReservationQueueClaimResult.ALREADY_TERMINAL);
        assertThat(workerStore.acknowledge(recovered, GROUP)).isEqualTo(1L);
    }

    private ReservationQueueWorkItem enqueueReadAndClaim(String workerId, Instant claimedAt) {
        String requestHash = ReservationIntentFingerprintFactory.create(PERFORMANCE, List.of(1L)).requestHash();
        RedisReservationQueueAdmissionStore admissionStore = new RedisReservationQueueAdmissionStore(
                redisTemplate, queueProperties, keyFactory, () -> OWNER
        );
        admissionStore.admit(new ReservationQueueAdmissionCommand(
                PERFORMANCE,
                TICKET,
                "b".repeat(64),
                ReservationQueuePayload.current(
                        7L,
                        ReservationIdempotencyKey.from("a0ebc4c9-8d82-47af-8127-1fc3d27e47a1"),
                        requestHash,
                        List.of(1L)
                ),
                START
        ));
        workerStore.ensureConsumerGroup(PERFORMANCE, GROUP);
        return readAndClaim(workerId, claimedAt);
    }

    private ReservationQueueWorkItem readAndClaim(String workerId, Instant claimedAt) {
        ReservationQueueStreamMessage message = workerStore.readNew(
                PERFORMANCE, GROUP, workerId, Duration.ofMillis(100)
        ).orElseThrow();
        ReservationQueueWorkItem item = decoder().decode(message);
        assertThat(workerStore.claim(
                item, workerId, claimedAt, Duration.ofSeconds(30)
        )).isIn(ReservationQueueClaimResult.CLAIMED, ReservationQueueClaimResult.ALREADY_OWNED);
        return item;
    }

    private ReservationQueueStreamPayloadDecoder decoder() {
        return new ReservationQueueStreamPayloadDecoder(List.of(new ReservationQueuePayloadV1Decoder()));
    }
}

package org.example.ticket.reservation.queue.repository.redis;

import org.example.ticket.reservation.common.factory.ReservationIntentFingerprintFactory;
import org.example.ticket.reservation.common.value.ReservationIdempotencyKey;
import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueAdmissionCommand;
import org.example.ticket.reservation.queue.dto.ReservationQueuePayload;
import org.example.ticket.reservation.queue.dto.ReservationQueueStreamMessage;
import org.example.ticket.reservation.queue.dto.ReservationQueueSuccessResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueTerminalCleanupResult;
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
class RedisReservationQueueMaintenanceStoreIntegrationTest {

    private static final long PERFORMANCE = 42L;
    private static final Instant START = Instant.parse("2026-08-12T10:00:00Z");
    private static final String GROUP = "booking-workers";
    private static final UUID FIRST_TICKET = UUID.fromString("f76f5ac8-a475-4e04-906a-1f54765f9770");
    private static final UUID SECOND_TICKET = UUID.fromString("e86f5ac8-a475-4e04-906a-1f54765f9771");
    private static final UUID OWNER = UUID.fromString("da64524f-ac82-45a8-9d38-4cd641b72343");

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private ReservationQueueProperties properties;
    private ReservationQueueKeyFactory keyFactory;
    private RedisReservationQueueMaintenanceStore maintenanceStore;

    @BeforeEach
    void setUp() {
        int port = Integer.parseInt(System.getenv().getOrDefault("RESERVATION_QUEUE_REDIS_PORT", "6389"));
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", port);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        properties = ReservationQueueProperties.defaults();
        keyFactory = new ReservationQueueKeyFactory();
        maintenanceStore = new RedisReservationQueueMaintenanceStore(redisTemplate, properties, keyFactory);
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void staleMappingConvergesToQueuedOrIsReleasedByTicketExistence() {
        ReservationQueueAdmissionRedisCommands commands = new ReservationQueueAdmissionRedisCommands(
                redisTemplate, properties, keyFactory
        );
        ReservationQueueAdmissionCommand existingTicket = command(FIRST_TICKET, START, 1L);
        commands.reserveIdempotency(existingTicket, OWNER.toString());
        commands.enqueue(existingTicket, OWNER.toString());
        redisTemplate.delete(keyFactory.enqueuingMappings());
        commands.reserveIdempotency(existingTicket, OWNER.toString());
        ReservationQueueAdmissionCommand missingTicket = command(SECOND_TICKET, START, 2L);
        commands.reserveIdempotency(missingTicket, OWNER.toString());

        var result = maintenanceStore.reconcileStaleMappings(
                START.plusSeconds(1), START.plusSeconds(2), 10
        );

        String existingMapping = keyFactory.idempotency(
                existingTicket.ownerHash(), existingTicket.payload().idempotencyKey().hash()
        );
        String missingMapping = keyFactory.idempotency(
                missingTicket.ownerHash(), missingTicket.payload().idempotencyKey().hash()
        );
        assertThat(result.repaired()).isEqualTo(1);
        assertThat(result.released()).isEqualTo(1);
        assertThat(redisTemplate.opsForHash().get(existingMapping, "state")).isEqualTo("QUEUED");
        assertThat(redisTemplate.hasKey(missingMapping)).isFalse();
        assertThat(redisTemplate.opsForZSet().size(keyFactory.enqueuingMappings())).isZero();
        assertThat(maintenanceStore.reconcileStaleMappings(
                START.plusSeconds(1), START.plusSeconds(3), 10
        ).repaired()).isZero();
    }

    @Test
    void missingActivePerformanceIsRestoredFromTicketWithItsRemainingTtl() {
        ReservationQueueAdmissionRedisCommands commands = new ReservationQueueAdmissionRedisCommands(
                redisTemplate, properties, keyFactory
        );
        ReservationQueueAdmissionCommand command = command(FIRST_TICKET, START, 1L);
        commands.reserveIdempotency(command, OWNER.toString());
        commands.enqueue(command, OWNER.toString());

        assertThat(redisTemplate.opsForZSet().score(
                keyFactory.activeRepairCandidates(), String.valueOf(PERFORMANCE)
        )).isNotNull();
        assertThat(maintenanceStore.repairActivePerformances(START.plusSeconds(1), 20)).isEqualTo(1);
        assertThat(redisTemplate.opsForZSet().score(
                keyFactory.activePerformanceTimes(), String.valueOf(PERFORMANCE)
        )).isNotNull();
        assertThat(redisTemplate.opsForZSet().size(keyFactory.activeRepairCandidates())).isZero();
        assertThat(maintenanceStore.repairActivePerformances(START.plusSeconds(2), 20)).isZero();
    }

    @Test
    void terminalCleanupPreservesPendingThenDeletesAckedEntryAndTicket() {
        ReservationQueueWorkItem item = enqueueReadAndClaim();
        RedisReservationQueueTerminalStore terminalStore = new RedisReservationQueueTerminalStore(
                redisTemplate, properties, keyFactory
        );
        terminalStore.completeSuccess(
                item,
                "worker-a",
                new ReservationQueueSuccessResult(
                        1, 20L, 10_000, "reservation-20",
                        LocalDateTime.parse("2026-08-12T10:07:00")
                ),
                START.plusSeconds(2)
        );
        Long resultTtl = redisTemplate.getExpire(
                keyFactory.ticket(PERFORMANCE, FIRST_TICKET),
                java.util.concurrent.TimeUnit.MILLISECONDS
        );
        assertThat(resultTtl).isGreaterThan(properties.idempotencyRetention().toMillis());
        assertThat(redisTemplate.opsForZSet().score(
                keyFactory.activeRepairCandidates(), String.valueOf(PERFORMANCE)
        )).isNotNull();

        assertThat(maintenanceStore.cleanupTerminalTickets(
                PERFORMANCE, GROUP, START.plusSeconds(3), 10
        )).isEqualTo(new ReservationQueueTerminalCleanupResult(1, 0));
        assertThat(redisTemplate.hasKey(keyFactory.ticket(PERFORMANCE, FIRST_TICKET))).isTrue();

        RedisReservationQueueWorkerStore workerStore = new RedisReservationQueueWorkerStore(
                redisTemplate, properties, keyFactory
        );
        assertThat(workerStore.acknowledge(item, GROUP)).isEqualTo(1L);
        assertThat(maintenanceStore.cleanupTerminalTickets(
                PERFORMANCE, GROUP, START.plusSeconds(3), 10
        )).isEqualTo(new ReservationQueueTerminalCleanupResult(1, 1));
        assertThat(redisTemplate.hasKey(keyFactory.ticket(PERFORMANCE, FIRST_TICKET))).isFalse();
        assertThat(redisTemplate.opsForStream().size(keyFactory.stream(PERFORMANCE))).isZero();
        assertThat(maintenanceStore.cleanupTerminalTickets(
                PERFORMANCE, GROUP, START.plusSeconds(3), 10
        )).isEqualTo(new ReservationQueueTerminalCleanupResult(0, 0));
    }

    private ReservationQueueWorkItem enqueueReadAndClaim() {
        RedisReservationQueueAdmissionStore admissionStore = new RedisReservationQueueAdmissionStore(
                redisTemplate, properties, keyFactory, () -> OWNER
        );
        admissionStore.admit(command(FIRST_TICKET, START, 1L));
        RedisReservationQueueWorkerStore workerStore = new RedisReservationQueueWorkerStore(
                redisTemplate, properties, keyFactory
        );
        workerStore.ensureConsumerGroup(PERFORMANCE, GROUP);
        ReservationQueueStreamMessage message = workerStore.readNew(
                PERFORMANCE, GROUP, "worker-a", Duration.ofMillis(100)
        ).orElseThrow();
        ReservationQueueWorkItem item = new ReservationQueueStreamPayloadDecoder(
                List.of(new ReservationQueuePayloadV1Decoder())
        ).decode(message);
        workerStore.claim(item, "worker-a", START.plusSeconds(1), Duration.ofSeconds(30));
        return item;
    }

    private ReservationQueueAdmissionCommand command(UUID ticketId, Instant enqueuedAt, long seatId) {
        String hash = ReservationIntentFingerprintFactory.create(PERFORMANCE, List.of(seatId)).requestHash();
        return new ReservationQueueAdmissionCommand(
                PERFORMANCE,
                ticketId,
                "b".repeat(64),
                ReservationQueuePayload.current(
                        7L,
                        ReservationIdempotencyKey.from(ticketId.toString()),
                        hash,
                        List.of(seatId)
                ),
                enqueuedAt
        );
    }
}

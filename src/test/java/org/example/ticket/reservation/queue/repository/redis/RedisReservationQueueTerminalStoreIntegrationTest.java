package org.example.ticket.reservation.queue.repository.redis;

import org.example.ticket.reservation.common.factory.ReservationIntentFingerprintFactory;
import org.example.ticket.reservation.common.value.ReservationIdempotencyKey;
import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueAdmissionCommand;
import org.example.ticket.reservation.queue.dto.ReservationQueuePayload;
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
class RedisReservationQueueTerminalStoreIntegrationTest {

    private static final UUID TICKET_ID = UUID.fromString("f76f5ac8-a475-4e04-906a-1f54765f9770");
    private static final UUID OWNER_TOKEN = UUID.fromString("da64524f-ac82-45a8-9d38-4cd641b72343");
    private static final String GROUP = "booking-workers";
    private static final String WORKER = "worker-a";

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private ReservationQueueKeyFactory keyFactory;
    private RedisReservationQueueWorkerStore workerStore;
    private RedisReservationQueueTerminalStore terminalStore;

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
        ReservationQueueProperties properties = ReservationQueueProperties.defaults();
        workerStore = new RedisReservationQueueWorkerStore(redisTemplate, properties, keyFactory);
        terminalStore = new RedisReservationQueueTerminalStore(redisTemplate, properties, keyFactory);
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void successCleansIndexesPersistsResultAndAllowsIdempotentAck() {
        ReservationQueueWorkItem item = enqueueReadAndClaim();
        ReservationQueueSuccessResult result = new ReservationQueueSuccessResult(
                1, 20L, 10_000, "reservation-20", LocalDateTime.parse("2026-08-12T10:07:00")
        );

        assertThat(terminalStore.completeSuccess(
                item, WORKER, result, Instant.parse("2026-08-12T10:00:02Z")
        )).isEqualTo(ReservationQueueTerminalResult.COMPLETED);
        assertThat(terminalStore.completeSuccess(
                item, WORKER, result, Instant.parse("2026-08-12T10:00:03Z")
        )).isEqualTo(ReservationQueueTerminalResult.ALREADY_TERMINAL);

        String ticketKey = keyFactory.ticket(42L, TICKET_ID);
        assertThat(redisTemplate.opsForHash().get(ticketKey, "status")).isEqualTo("SUCCEEDED");
        assertThat(redisTemplate.opsForHash().get(ticketKey, "reservationId")).isEqualTo("20");
        assertThat(redisTemplate.opsForZSet().score(keyFactory.admitted(42L), TICKET_ID.toString())).isNull();
        assertThat(redisTemplate.opsForZSet().score(keyFactory.waiting(42L), TICKET_ID.toString())).isNull();
        assertThat(redisTemplate.opsForZSet().score(keyFactory.processing(42L), TICKET_ID.toString())).isNull();
        assertThat(redisTemplate.opsForZSet().score(keyFactory.deadline(42L), TICKET_ID.toString())).isNull();
        assertThat(workerStore.acknowledge(item, GROUP)).isEqualTo(1L);
    }

    @Test
    void wrongWorkerCannotFinalizeProcessingTicket() {
        ReservationQueueWorkItem item = enqueueReadAndClaim();

        assertThat(terminalStore.completeFinal(
                item,
                "worker-b",
                "SEAT_ALREADY_RESERVED",
                Instant.parse("2026-08-12T10:00:02Z")
        )).isEqualTo(ReservationQueueTerminalResult.OWNER_MISMATCH);

        String ticketKey = keyFactory.ticket(42L, TICKET_ID);
        assertThat(redisTemplate.opsForHash().get(ticketKey, "status")).isEqualTo("PROCESSING");
        assertThat(redisTemplate.opsForZSet().score(keyFactory.processing(42L), TICKET_ID.toString()))
                .isNotNull();
    }

    private ReservationQueueWorkItem enqueueReadAndClaim() {
        ReservationIdempotencyKey idempotencyKey = ReservationIdempotencyKey.from(
                "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1"
        );
        String requestHash = ReservationIntentFingerprintFactory.create(42L, List.of(1L)).requestHash();
        RedisReservationQueueAdmissionStore admissionStore = new RedisReservationQueueAdmissionStore(
                redisTemplate,
                ReservationQueueProperties.defaults(),
                keyFactory,
                () -> OWNER_TOKEN
        );
        admissionStore.admit(new ReservationQueueAdmissionCommand(
                42L,
                TICKET_ID,
                "b".repeat(64),
                ReservationQueuePayload.current(7L, idempotencyKey, requestHash, List.of(1L)),
                Instant.parse("2026-08-12T10:00:00Z")
        ));
        workerStore.ensureConsumerGroup(42L, GROUP);
        ReservationQueueStreamMessage message = workerStore.readNew(
                42L, GROUP, WORKER, Duration.ofMillis(100)
        ).orElseThrow();
        ReservationQueueWorkItem item = new ReservationQueueStreamPayloadDecoder(
                List.of(new ReservationQueuePayloadV1Decoder())
        ).decode(message);
        assertThat(workerStore.claim(
                item,
                WORKER,
                Instant.parse("2026-08-12T10:00:01Z"),
                Duration.ofSeconds(30)
        ).name()).isEqualTo("CLAIMED");
        return item;
    }
}

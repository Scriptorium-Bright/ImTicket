package org.example.ticket.reservation.queue.service;

import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.reservation.booking.dto.response.ReservationCreateResponse;
import org.example.ticket.reservation.booking.service.ReservationClaimExecutionService;
import org.example.ticket.reservation.booking.util.ReservationFailureClassifier;
import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.config.ReservationQueueWorkerProperties;
import org.example.ticket.reservation.queue.constant.ReservationQueueStatus;
import org.example.ticket.reservation.queue.dto.request.ReservationQueueApiRequest;
import org.example.ticket.reservation.queue.dto.response.ReservationQueueEnqueueResponse;
import org.example.ticket.reservation.queue.dto.response.ReservationQueueStatusResponse;
import org.example.ticket.reservation.queue.repository.redis.RedisReservationQueueAdmissionStore;
import org.example.ticket.reservation.queue.repository.redis.RedisReservationQueueExpiryIndex;
import org.example.ticket.reservation.queue.repository.redis.RedisReservationQueueTerminalStore;
import org.example.ticket.reservation.queue.repository.redis.RedisReservationQueueTicketStore;
import org.example.ticket.reservation.queue.repository.redis.RedisReservationQueueWorkerStore;
import org.example.ticket.reservation.queue.repository.redis.ReservationQueueKeyFactory;
import org.example.ticket.reservation.queue.util.ReservationQueueIdentityHasher;
import org.example.ticket.reservation.queue.util.worker.ReservationQueuePayloadV1Decoder;
import org.example.ticket.reservation.queue.util.worker.ReservationQueueStreamPayloadDecoder;
import org.example.ticket.reservation.queue.util.worker.ReservationQueueWorkerPermits;
import org.example.ticket.reservation.queue.util.worker.ReservationQueueWorkerPoller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "RESERVATION_QUEUE_REDIS_INTEGRATION", matches = "true")
class ReservationQueueWorkerFlowIntegrationTest {

    private static final String GROUP = "booking-workers";
    private static final String WORKER = "worker-a";
    private static final String KEY = "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-12T10:00:00Z"),
            ZoneOffset.UTC
    );

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        int port = Integer.parseInt(System.getenv().getOrDefault("RESERVATION_QUEUE_REDIS_PORT", "6389"));
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", port);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void enqueueToWorkerSuccessStatusAndAckRunsAsOneFlow() {
        ReservationQueueProperties queueProperties = ReservationQueueProperties.defaults();
        ReservationQueueWorkerProperties workerProperties = new ReservationQueueWorkerProperties(
                true, GROUP, WORKER, 1, 1, Duration.ofMillis(10), Duration.ofMillis(10)
        );
        ReservationQueueKeyFactory keyFactory = new ReservationQueueKeyFactory();
        RedisReservationQueueTicketStore ticketStore = new RedisReservationQueueTicketStore(
                redisTemplate, queueProperties, keyFactory
        );
        ReservationQueueIdentityHasher identityHasher = new ReservationQueueIdentityHasher();
        ReservationQueueService queueService = new ReservationQueueService(
                new RedisReservationQueueAdmissionStore(redisTemplate, queueProperties, keyFactory),
                ticketStore,
                identityHasher,
                queueProperties,
                CLOCK
        );
        ReservationQueueEnqueueResponse enqueue = queueService.enqueue(
                7L,
                "0xowner",
                KEY,
                new ReservationQueueApiRequest(42L, List.of(1L))
        );

        RedisReservationQueueWorkerStore workerStore = new RedisReservationQueueWorkerStore(
                redisTemplate, queueProperties, keyFactory
        );
        ReservationClaimExecutionService executionService = mock(ReservationClaimExecutionService.class);
        when(executionService.execute(any(), any(), any(), any())).thenReturn(
                ReservationCreateResponse.builder()
                        .id(20L)
                        .totalPrice(10_000)
                        .orderUid("reservation-20")
                        .expiredTime(LocalDateTime.parse("2026-08-12T10:07:00"))
                        .responses(List.of())
                        .build()
        );
        ReservationQueueProcessor processor = new ReservationQueueProcessor(
                executionService,
                mock(MemberRepository.class),
                new ReservationFailureClassifier(),
                new RedisReservationQueueTerminalStore(redisTemplate, queueProperties, keyFactory),
                workerStore,
                workerProperties,
                CLOCK
        );
        ReservationQueueWorkerPoller poller = new ReservationQueueWorkerPoller(
                workerStore,
                new RedisReservationQueueExpiryIndex(redisTemplate, keyFactory),
                new ReservationQueueStreamPayloadDecoder(List.of(new ReservationQueuePayloadV1Decoder())),
                new ReservationQueueWorkerPermits(1, 1),
                processor,
                Runnable::run,
                workerProperties,
                queueProperties.processingLease(),
                CLOCK
        );

        assertThat(poller.pollOnce()).isEqualTo(1);
        ReservationQueueStatusResponse status = queueService.status(
                "0xowner",
                42L,
                enqueue.ticketId()
        );

        assertThat(status.status()).isEqualTo(ReservationQueueStatus.SUCCEEDED);
        assertThat(status.result().reservationId()).isEqualTo(20L);
        assertThat(status.position()).isNull();
        assertThat(redisTemplate.opsForStream().pending(
                keyFactory.stream(42L),
                GROUP
        ).getTotalPendingMessages()).isZero();
        verify(executionService).execute(any(), any(), any(), any());
    }
}

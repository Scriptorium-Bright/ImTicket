package org.example.ticket.reservation.queue;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Admission Lua script의 key와 argument 구성, 반환값 해석을 담당한다. */
final class ReservationQueueAdmissionRedisCommands {

    private static final DefaultRedisScript<String> RESERVE_IDEMPOTENCY = script(
            "redis/reservation-queue/reservation_queue_reserve_idempotency.lua");
    private static final DefaultRedisScript<String> ENQUEUE = script(
            "redis/reservation-queue/reservation_queue_enqueue.lua");
    private static final DefaultRedisScript<String> MARK_IDEMPOTENCY_QUEUED = script(
            "redis/reservation-queue/reservation_queue_mark_idempotency_queued.lua");
    private static final DefaultRedisScript<String> RELEASE_IDEMPOTENCY = script(
            "redis/reservation-queue/reservation_queue_release_idempotency.lua");

    private final StringRedisTemplate redisTemplate;
    private final ReservationQueueProperties properties;
    private final ReservationQueueKeyFactory keyFactory;

    ReservationQueueAdmissionRedisCommands(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory must not be null");
    }

    IdempotencyReservation reserveIdempotency(
            ReservationQueueAdmissionCommand command,
            String ownerToken
    ) {
        String result = execute(
                RESERVE_IDEMPOTENCY,
                List.of(keyFactory.idempotency(command.ownerHash(), command.idempotencyKeyHash())),
                command.requestHash(),
                command.ticketId().toString(),
                String.valueOf(command.performanceTimeId()),
                ownerToken,
                String.valueOf(command.enqueuedAt().toEpochMilli()),
                String.valueOf(properties.idempotencyRetention().toMillis())
        );
        if ("CREATED".equals(result)) {
            return IdempotencyReservation.created();
        }
        if ("CONFLICT".equals(result)) {
            return IdempotencyReservation.conflict();
        }
        String[] parts = result.split("\\|", -1);
        if (parts.length == 4 && "EXISTING".equals(parts[0])) {
            try {
                return IdempotencyReservation.existing(
                        parts[1],
                        UUID.fromString(parts[2]),
                        Long.parseLong(parts[3])
                );
            } catch (IllegalArgumentException exception) {
                throw new ReservationQueueStorageException(
                        "Invalid idempotency reservation result: " + result,
                        exception
                );
            }
        }
        throw new ReservationQueueStorageException("Unexpected idempotency reservation result: " + result);
    }

    EnqueueResult enqueue(ReservationQueueAdmissionCommand command) {
        long performanceTimeId = command.performanceTimeId();
        String result = execute(
                ENQUEUE,
                List.of(
                        keyFactory.admitted(performanceTimeId),
                        keyFactory.waiting(performanceTimeId),
                        keyFactory.deadline(performanceTimeId),
                        keyFactory.sequence(performanceTimeId),
                        keyFactory.ticket(performanceTimeId, command.ticketId()),
                        keyFactory.stream(performanceTimeId)
                ),
                command.ticketId().toString(),
                String.valueOf(performanceTimeId),
                command.ownerHash(),
                command.requestHash(),
                command.serializedSeatIds(),
                String.valueOf(command.enqueuedAt().toEpochMilli()),
                String.valueOf(command.deadline(properties).toEpochMilli()),
                String.valueOf(properties.maxDepth()),
                String.valueOf(properties.ticketRetention().toMillis())
        );
        if ("FULL".equals(result)) {
            return EnqueueResult.queueFull();
        }
        String[] parts = result.split("\\|", -1);
        if (parts.length == 3 && "ACCEPTED".equals(parts[0])) {
            try {
                return EnqueueResult.accepted(Long.parseLong(parts[1]), parts[2]);
            } catch (NumberFormatException exception) {
                throw new ReservationQueueStorageException("Invalid enqueue result: " + result, exception);
            }
        }
        throw new ReservationQueueStorageException("Unexpected enqueue result: " + result);
    }

    void markIdempotencyQueued(ReservationQueueAdmissionCommand command, String ownerToken) {
        String result = execute(
                MARK_IDEMPOTENCY_QUEUED,
                List.of(keyFactory.idempotency(command.ownerHash(), command.idempotencyKeyHash())),
                ownerToken,
                command.ticketId().toString(),
                String.valueOf(command.enqueuedAt().toEpochMilli()),
                String.valueOf(properties.idempotencyRetention().toMillis())
        );
        if (!"MARKED".equals(result) && !"ALREADY_QUEUED".equals(result)) {
            throw new ReservationQueueStorageException(
                    "Idempotency mapping was not marked as queued: " + result
            );
        }
    }

    void releaseIdempotency(ReservationQueueAdmissionCommand command, String ownerToken) {
        String result = execute(
                RELEASE_IDEMPOTENCY,
                List.of(keyFactory.idempotency(command.ownerHash(), command.idempotencyKeyHash())),
                ownerToken,
                command.ticketId().toString()
        );
        if (!"RELEASED".equals(result) && !"MISSING".equals(result)) {
            throw new ReservationQueueStorageException("Idempotency mapping was not released: " + result);
        }
    }

    void refreshActivePerformance(ReservationQueueAdmissionCommand command) {
        try {
            redisTemplate.opsForZSet().add(
                    keyFactory.activePerformanceTimes(),
                    String.valueOf(command.performanceTimeId()),
                    command.enqueuedAt().plus(properties.ticketRetention()).toEpochMilli()
            );
        } catch (DataAccessException ignored) {
            // Ticket과 Stream은 저장된 상태다. 만료 조회에서 registry를 다시 보완한다.
        }
    }

    private String execute(DefaultRedisScript<String> script, List<String> keys, String... arguments) {
        String result = redisTemplate.execute(script, keys, (Object[]) arguments);
        if (result == null) {
            throw new ReservationQueueStorageException("Redis script returned no result");
        }
        return result;
    }

    private static DefaultRedisScript<String> script(String location) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(String.class);
        return script;
    }

    enum ReservationKind {
        CREATED,
        EXISTING,
        CONFLICT
    }

    record IdempotencyReservation(
            ReservationKind kind,
            String state,
            UUID ticketId,
            long performanceTimeId
    ) {
        static IdempotencyReservation created() {
            return new IdempotencyReservation(ReservationKind.CREATED, null, null, 0L);
        }

        static IdempotencyReservation conflict() {
            return new IdempotencyReservation(ReservationKind.CONFLICT, null, null, 0L);
        }

        static IdempotencyReservation existing(String state, UUID ticketId, long performanceTimeId) {
            if (performanceTimeId <= 0) {
                throw new IllegalArgumentException("performanceTimeId must be positive");
            }
            return new IdempotencyReservation(ReservationKind.EXISTING, state, ticketId, performanceTimeId);
        }
    }

    record EnqueueResult(boolean full, long sequence, String streamId) {
        static EnqueueResult queueFull() {
            return new EnqueueResult(true, 0L, null);
        }

        static EnqueueResult accepted(long sequence, String streamId) {
            if (sequence <= 0 || streamId == null || streamId.isBlank()) {
                throw new IllegalArgumentException("Accepted enqueue result is invalid");
            }
            return new EnqueueResult(false, sequence, streamId);
        }
    }
}

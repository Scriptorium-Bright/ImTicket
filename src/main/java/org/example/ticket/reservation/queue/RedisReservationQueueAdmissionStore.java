package org.example.ticket.reservation.queue;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Idempotency 선점과 bounded enqueue의 실행 순서와 실패 정책을 담당한다. */
public final class RedisReservationQueueAdmissionStore implements ReservationQueueAdmissionStore {

    private final ReservationQueueAdmissionRedisCommands redisCommands;
    private final Supplier<UUID> ownerTokenSupplier;

    public RedisReservationQueueAdmissionStore(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory
    ) {
        this(new ReservationQueueAdmissionRedisCommands(redisTemplate, properties, keyFactory), UUID::randomUUID);
    }

    RedisReservationQueueAdmissionStore(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory,
            Supplier<UUID> ownerTokenSupplier
    ) {
        this(new ReservationQueueAdmissionRedisCommands(redisTemplate, properties, keyFactory), ownerTokenSupplier);
    }

    private RedisReservationQueueAdmissionStore(
            ReservationQueueAdmissionRedisCommands redisCommands,
            Supplier<UUID> ownerTokenSupplier
    ) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands must not be null");
        this.ownerTokenSupplier = Objects.requireNonNull(ownerTokenSupplier, "ownerTokenSupplier must not be null");
    }

    @Override
    public ReservationQueueAdmissionResult admit(ReservationQueueAdmissionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String ownerToken = Objects.requireNonNull(
                ownerTokenSupplier.get(),
                "ownerToken must not be null"
        ).toString();
        ReservationQueueAdmissionRedisCommands.IdempotencyReservation reservation =
                redisCommands.reserveIdempotency(command, ownerToken);

        if (reservation.kind() == ReservationQueueAdmissionRedisCommands.ReservationKind.CONFLICT) {
            return ReservationQueueAdmissionResult.rejected(
                    ReservationQueueAdmissionResult.Outcome.IDEMPOTENCY_CONFLICT,
                    command.performanceTimeId()
            );
        }
        if (reservation.kind() == ReservationQueueAdmissionRedisCommands.ReservationKind.EXISTING) {
            return existingResult(reservation);
        }

        ReservationQueueAdmissionRedisCommands.EnqueueResult enqueueResult = redisCommands.enqueue(command);
        if (enqueueResult.full()) {
            redisCommands.releaseIdempotency(command, ownerToken);
            return ReservationQueueAdmissionResult.rejected(
                    ReservationQueueAdmissionResult.Outcome.QUEUE_FULL,
                    command.performanceTimeId()
            );
        }

        redisCommands.markIdempotencyQueued(command, ownerToken);
        redisCommands.refreshActivePerformance(command);
        return ReservationQueueAdmissionResult.accepted(
                command.ticketId(),
                command.performanceTimeId(),
                enqueueResult.sequence(),
                enqueueResult.streamId()
        );
    }

    private ReservationQueueAdmissionResult existingResult(
            ReservationQueueAdmissionRedisCommands.IdempotencyReservation reservation
    ) {
        ReservationQueueAdmissionResult.Outcome outcome = switch (reservation.state()) {
            case "QUEUED" -> ReservationQueueAdmissionResult.Outcome.EXISTING;
            case "ENQUEUING" -> ReservationQueueAdmissionResult.Outcome.ENQUEUE_IN_PROGRESS;
            default -> throw new IllegalStateException("Unexpected idempotency state: " + reservation.state());
        };
        return ReservationQueueAdmissionResult.existing(
                outcome,
                reservation.ticketId(),
                reservation.performanceTimeId()
        );
    }
}

package org.example.ticket.reservation.queue.repository.redis;

import org.example.ticket.reservation.queue.dto.ReservationQueueAdmissionCommand;
import org.example.ticket.reservation.queue.dto.ReservationQueueAdmissionResult;
import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.repository.ReservationQueueAdmissionStore;
import org.example.ticket.reservation.queue.exception.ReservationQueueStorageException;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Idempotency 선점과 bounded enqueue의 실행 순서와 실패 정책을 담당한다. */
public final class RedisReservationQueueAdmissionStore implements ReservationQueueAdmissionStore {

    private final ReservationQueueAdmissionRedisCommands redisCommands;
    private final Supplier<UUID> ownerTokenSupplier;

    /**
     * Redis template과 Queue 설정으로 admission 명령 실행기를 구성한다.
     * 각 접수에는 무작위 owner token을 사용한다.
     */
    public RedisReservationQueueAdmissionStore(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory
    ) {
        this(new ReservationQueueAdmissionRedisCommands(redisTemplate, properties, keyFactory), UUID::randomUUID);
    }

    /**
     * 테스트가 결정적인 owner token 공급자를 주입할 수 있게 구성한다.
     * 실제 Redis 명령 계약은 public 생성자와 동일하게 유지한다.
     */
    RedisReservationQueueAdmissionStore(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory,
            Supplier<UUID> ownerTokenSupplier
    ) {
        this(new ReservationQueueAdmissionRedisCommands(redisTemplate, properties, keyFactory), ownerTokenSupplier);
    }

    /**
     * Redis 명령 실행기와 owner token 공급자를 최종 필드에 보관한다.
     * 모든 생성 경로의 null 검증을 한곳에서 수행한다.
     */
    private RedisReservationQueueAdmissionStore(
            ReservationQueueAdmissionRedisCommands redisCommands,
            Supplier<UUID> ownerTokenSupplier
    ) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands must not be null");
        this.ownerTokenSupplier = Objects.requireNonNull(ownerTokenSupplier, "ownerTokenSupplier must not be null");
    }

    /**
     * 멱등 mapping 선점부터 bounded enqueue 완료까지 순서대로 실행한다.
     * 기존 요청, 충돌, Queue full과 신규 접수를 명시적 결과로 반환한다.
     */
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

        ReservationQueueAdmissionRedisCommands.EnqueueResult enqueueResult = redisCommands.enqueue(command, ownerToken);
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

    /**
     * 기존 idempotency mapping 상태를 서비스용 admission 결과로 변환한다.
     * 알려지지 않은 상태는 저장소 계약 오류로 처리한다.
     */
    private ReservationQueueAdmissionResult existingResult(
            ReservationQueueAdmissionRedisCommands.IdempotencyReservation reservation
    ) {
        ReservationQueueAdmissionResult.Outcome outcome = switch (reservation.state()) {
            case "QUEUED" -> ReservationQueueAdmissionResult.Outcome.EXISTING;
            case "ENQUEUING" -> ReservationQueueAdmissionResult.Outcome.ENQUEUE_IN_PROGRESS;
            default -> throw new ReservationQueueStorageException(
                    "Unexpected idempotency state: " + reservation.state()
            );
        };
        return ReservationQueueAdmissionResult.existing(
                outcome,
                reservation.ticketId(),
                reservation.performanceTimeId()
        );
    }
}

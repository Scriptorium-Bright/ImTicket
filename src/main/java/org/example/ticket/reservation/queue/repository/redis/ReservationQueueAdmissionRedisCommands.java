package org.example.ticket.reservation.queue.repository.redis;

import org.example.ticket.reservation.queue.dto.ReservationQueueAdmissionCommand;
import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.exception.ReservationQueueStorageException;

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

    /**
     * admission Lua 실행에 필요한 Redis template, 설정과 key factory를 보관한다.
     * 모든 명령이 동일한 TTL과 key namespace를 사용하게 한다.
     */
    ReservationQueueAdmissionRedisCommands(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory must not be null");
    }

    /**
     * 소유자와 멱등 키 조합의 mapping을 Lua로 선점한다.
     * 생성, 기존 ticket과 요청 hash 충돌 결과를 구조화해 반환한다.
     */
    IdempotencyReservation reserveIdempotency(
            ReservationQueueAdmissionCommand command,
            String ownerToken
    ) {
        String result = execute(
                RESERVE_IDEMPOTENCY,
                List.of(keyFactory.idempotency(command.ownerHash(), command.payload().idempotencyKey().hash())),
                command.payload().requestHash(),
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

    /**
     * bounded enqueue Lua로 ticket Hash, ZSET과 Stream entry를 함께 저장한다.
     * Queue full 또는 생성된 순번과 Stream ID를 반환한다.
     */
    EnqueueResult enqueue(ReservationQueueAdmissionCommand command, String ownerToken) {
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
                ownerToken,
                String.valueOf(command.payload().schemaVersion()),
                String.valueOf(command.payload().memberId()),
                command.payload().idempotencyKey().value(),
                command.payload().idempotencyKey().hash(),
                command.payload().requestHash(),
                command.payload().serializedSeatIds(),
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

    /**
     * enqueue가 끝난 mapping을 ENQUEUING에서 QUEUED로 전환한다.
     * owner token과 ticket ID가 일치하는 mapping만 갱신한다.
     */
    void markIdempotencyQueued(ReservationQueueAdmissionCommand command, String ownerToken) {
        String result = execute(
                MARK_IDEMPOTENCY_QUEUED,
                List.of(keyFactory.idempotency(command.ownerHash(), command.payload().idempotencyKey().hash())),
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

    /**
     * Queue full로 ticket이 생성되지 않은 mapping을 해제한다.
     * 현재 접수의 owner token과 ticket ID가 일치할 때만 삭제한다.
     */
    void releaseIdempotency(ReservationQueueAdmissionCommand command, String ownerToken) {
        String result = execute(
                RELEASE_IDEMPOTENCY,
                List.of(keyFactory.idempotency(command.ownerHash(), command.payload().idempotencyKey().hash())),
                ownerToken,
                command.ticketId().toString()
        );
        if (!"RELEASED".equals(result) && !"MISSING".equals(result)) {
            throw new ReservationQueueStorageException("Idempotency mapping was not released: " + result);
        }
    }

    /**
     * 접수된 회차를 active performance ZSET에 추가하거나 보존 시각을 갱신한다.
     * 보조 index 갱신 실패는 이미 저장된 ticket과 Stream 결과를 바꾸지 않는다.
     */
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

    /**
     * 지정한 key와 인자로 Redis Lua script를 실행한다.
     * 결과가 없으면 저장 상태를 판단할 수 없으므로 저장소 오류를 발생시킨다.
     */
    private String execute(DefaultRedisScript<String> script, List<String> keys, String... arguments) {
        String result = redisTemplate.execute(script, keys, (Object[]) arguments);
        if (result == null) {
            throw new ReservationQueueStorageException("Redis script returned no result");
        }
        return result;
    }

    /**
     * classpath Lua 파일을 문자열 결과 script 객체로 만든다.
     * 정적 명령별 script 정의가 같은 로딩 방식을 사용하게 한다.
     */
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
        /**
         * 현재 요청이 mapping 선점에 성공한 결과를 만든다.
         * 후속 enqueue가 새 ticket 생성을 계속 진행하게 한다.
         */
        static IdempotencyReservation created() {
            return new IdempotencyReservation(ReservationKind.CREATED, null, null, 0L);
        }

        /**
         * 같은 멱등 키의 request hash가 다른 충돌 결과를 만든다.
         * admission 저장소가 ticket 생성 없이 요청을 거절하게 한다.
         */
        static IdempotencyReservation conflict() {
            return new IdempotencyReservation(ReservationKind.CONFLICT, null, null, 0L);
        }

        /**
         * 기존 mapping의 상태, ticket과 회차를 복원한 결과를 만든다.
         * 같은 요청이 원래 ticket으로 수렴하도록 필요한 식별자를 보존한다.
         */
        static IdempotencyReservation existing(String state, UUID ticketId, long performanceTimeId) {
            if (performanceTimeId <= 0) {
                throw new IllegalArgumentException("performanceTimeId must be positive");
            }
            return new IdempotencyReservation(ReservationKind.EXISTING, state, ticketId, performanceTimeId);
        }
    }

    record EnqueueResult(boolean full, long sequence, String streamId) {
        /**
         * admission 수용량을 초과한 enqueue 결과를 만든다.
         * 생성된 ticket이나 Stream 정보가 없음을 명시한다.
         */
        static EnqueueResult queueFull() {
            return new EnqueueResult(true, 0L, null);
        }

        /**
         * Redis에 저장된 ticket 순번과 Stream ID로 성공 결과를 만든다.
         * 값이 유효하지 않으면 script 반환 계약 위반으로 처리한다.
         */
        static EnqueueResult accepted(long sequence, String streamId) {
            if (sequence <= 0 || streamId == null || streamId.isBlank()) {
                throw new IllegalArgumentException("Accepted enqueue result is invalid");
            }
            return new EnqueueResult(false, sequence, streamId);
        }
    }
}

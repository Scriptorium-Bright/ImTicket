package org.example.ticket.reservation.queue.repository.redis;

import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueTicketSnapshot;
import org.example.ticket.reservation.queue.exception.ReservationQueueStorageException;
import org.example.ticket.reservation.queue.repository.ReservationQueueTicketStore;
import org.example.ticket.reservation.queue.constant.ReservationQueueStatus;
import org.example.ticket.reservation.queue.dto.ReservationQueuePayload;
import org.example.ticket.reservation.queue.dto.ReservationQueueSuccessResult;
import org.example.ticket.reservation.common.value.ReservationIdempotencyKey;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Redis ticket Hash 조회와 deadline 만료 전이를 담당한다. */
public final class RedisReservationQueueTicketStore implements ReservationQueueTicketStore {

    private static final DefaultRedisScript<String> EXPIRE_TICKET = script(
            "redis/reservation-queue/reservation_queue_expire_ticket.lua");

    private final StringRedisTemplate redisTemplate;
    private final ReservationQueueProperties properties;
    private final ReservationQueueKeyFactory keyFactory;

    /**
     * ticket Hash 조회와 만료 Lua에 필요한 Redis 의존성을 주입한다.
     * Queue 설정의 보존 시간을 만료 전이에 함께 사용한다.
     */
    public RedisReservationQueueTicketStore(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory must not be null");
    }

    /**
     * Redis ticket Hash를 읽어 검증된 snapshot으로 복원한다.
     * key와 저장 필드가 불일치하거나 payload가 손상되면 저장소 오류를 발생시킨다.
     */
    @Override
    public Optional<ReservationQueueTicketSnapshot> find(long performanceTimeId, UUID ticketId) {
        String ticketKey = keyFactory.ticket(performanceTimeId, ticketId);
        HashOperations<String, Object, Object> hashOperations = redisTemplate.opsForHash();
        Map<Object, Object> fields = hashOperations.entries(ticketKey);
        if (fields.isEmpty()) {
            return Optional.empty();
        }

        try {
            UUID storedTicketId = UUID.fromString(required(fields, "ticketId"));
            long storedPerformanceTimeId = Long.parseLong(required(fields, "performanceTimeId"));
            if (!ticketId.equals(storedTicketId) || performanceTimeId != storedPerformanceTimeId) {
                throw new ReservationQueueStorageException("Ticket identity does not match its Redis key");
            }

            ReservationQueueStatus status = ReservationQueueStatus.valueOf(required(fields, "status"));
            ReservationIdempotencyKey idempotencyKey = ReservationIdempotencyKey.restore(
                    required(fields, "idempotencyKey"),
                    required(fields, "idempotencyKeyHash")
            );
            ReservationQueuePayload payload = new ReservationQueuePayload(
                    Integer.parseInt(required(fields, "payloadSchemaVersion")),
                    Long.parseLong(required(fields, "memberId")),
                    idempotencyKey,
                    required(fields, "requestHash"),
                    seatIds(fields)
            );
            Long position = waitingPosition(performanceTimeId, ticketId, status);
            return Optional.of(new ReservationQueueTicketSnapshot(
                    storedTicketId,
                    storedPerformanceTimeId,
                    required(fields, "ownerHash"),
                    UUID.fromString(required(fields, "ownerToken")),
                    payload,
                    status,
                    Long.parseLong(required(fields, "sequence")),
                    position,
                    Instant.ofEpochMilli(Long.parseLong(required(fields, "enqueuedAt"))),
                    Instant.ofEpochMilli(Long.parseLong(required(fields, "deadlineAt"))),
                    successResult(fields, status),
                    finalErrorCode(fields, status)
            ));
        } catch (ReservationQueueStorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ReservationQueueStorageException("Queue ticket Hash is invalid", exception);
        }
    }

    /**
     * ticket 만료 Lua를 실행해 관련 ZSET과 Hash 상태를 함께 변경한다.
     * 실제 만료, 미존재와 만료 불가 결과를 boolean 계약으로 변환한다.
     */
    @Override
    public boolean expireIfDue(long performanceTimeId, UUID ticketId, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        String result = redisTemplate.execute(
                EXPIRE_TICKET,
                java.util.List.of(
                        keyFactory.admitted(performanceTimeId),
                        keyFactory.waiting(performanceTimeId),
                        keyFactory.deadline(performanceTimeId),
                        keyFactory.ticket(performanceTimeId, ticketId)
                ),
                ticketId.toString(),
                String.valueOf(now.toEpochMilli()),
                String.valueOf(properties.ticketRetention().toMillis())
        );
        if ("EXPIRED".equals(result)) {
            return true;
        }
        if ("MISSING".equals(result) || "NOT_DUE".equals(result) || "NOT_EXPIRABLE".equals(result)) {
            return false;
        }
        throw new ReservationQueueStorageException("Unexpected ticket expiry result: " + result);
    }

    /**
     * WAITING으로 공개되는 ticket의 1부터 시작하는 대기 순번을 계산한다.
     * 대기 상태가 아니거나 ZSET에 없으면 순번을 반환하지 않는다.
     */
    private Long waitingPosition(long performanceTimeId, UUID ticketId, ReservationQueueStatus status) {
        if (status.visibleStatus() != ReservationQueueStatus.WAITING) {
            return null;
        }
        Long zeroBasedRank = redisTemplate.opsForZSet().rank(
                keyFactory.waiting(performanceTimeId),
                ticketId.toString()
        );
        return zeroBasedRank == null ? null : zeroBasedRank + 1;
    }

    /**
     * ticket Hash에서 필수 필드를 문자열로 읽는다.
     * 누락되거나 빈 값이면 손상된 Redis 데이터로 처리한다.
     */
    private String required(Map<Object, Object> fields, String name) {
        Object value = fields.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new ReservationQueueStorageException("Queue ticket field is missing: " + name);
        }
        return value.toString();
    }

    /**
     * 쉼표로 저장된 좌석 ID 문자열을 숫자 목록으로 복원한다.
     * 형식 오류는 상위 snapshot 변환에서 저장소 오류로 감싼다.
     */
    private List<Long> seatIds(Map<Object, Object> fields) {
        return Arrays.stream(required(fields, "seatIds").split(",", -1))
                .map(Long::parseLong)
                .toList();
    }

    /**
     * SUCCEEDED ticket의 versioned 예약 결과 field를 복원한다.
     * 다른 상태에서는 성공 결과가 존재하지 않는 것으로 반환한다.
     */
    private ReservationQueueSuccessResult successResult(
            Map<Object, Object> fields,
            ReservationQueueStatus status
    ) {
        if (status != ReservationQueueStatus.SUCCEEDED) {
            return null;
        }
        return new ReservationQueueSuccessResult(
                Integer.parseInt(required(fields, "resultSchemaVersion")),
                Long.parseLong(required(fields, "reservationId")),
                Integer.parseInt(required(fields, "totalPrice")),
                required(fields, "orderUid"),
                LocalDateTime.parse(required(fields, "expiredTime"))
        );
    }

    /**
     * FAILED_FINAL ticket에 저장된 공개 오류 code를 읽는다.
     * 다른 상태에서는 오류 결과가 존재하지 않는 것으로 반환한다.
     */
    private String finalErrorCode(Map<Object, Object> fields, ReservationQueueStatus status) {
        return status == ReservationQueueStatus.FAILED_FINAL
                ? required(fields, "errorCode")
                : null;
    }

    /**
     * classpath의 Lua 파일을 문자열 결과 script로 구성한다.
     * 정적 초기화 시 만료 명령의 실행 계약을 고정한다.
     */
    private static DefaultRedisScript<String> script(String location) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(String.class);
        return script;
    }
}

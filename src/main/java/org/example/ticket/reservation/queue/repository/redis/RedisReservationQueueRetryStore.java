package org.example.ticket.reservation.queue.repository.redis;

import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.config.ReservationQueueRetryProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueRetryResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;
import org.example.ticket.reservation.queue.exception.ReservationQueueStorageException;
import org.example.ticket.reservation.queue.repository.ReservationQueueRetryStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Redis Lua로 retry scheduling과 due Stream 승격을 구현한다. */
public final class RedisReservationQueueRetryStore implements ReservationQueueRetryStore {

    private static final String EXHAUSTED_CODE = "QUEUE_RETRY_EXHAUSTED";
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z0-9_]{1,64}");
    private static final DefaultRedisScript<String> SCHEDULE = script(
            "redis/reservation-queue/reservation_queue_schedule_retry.lua"
    );
    private static final DefaultRedisScript<String> PROMOTE = script(
            "redis/reservation-queue/reservation_queue_promote_retry.lua"
    );

    private final StringRedisTemplate redisTemplate;
    private final ReservationQueueProperties queueProperties;
    private final ReservationQueueRetryProperties retryProperties;
    private final ReservationQueueKeyFactory keyFactory;

    /**
     * Retry Lua에 필요한 Redis template, 보존 설정과 backoff 정책을 연결한다.
     * 모든 retry key는 같은 공연 회차 hash tag를 사용한다.
     */
    public RedisReservationQueueRetryStore(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties queueProperties,
            ReservationQueueRetryProperties retryProperties,
            ReservationQueueKeyFactory keyFactory
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.queueProperties = Objects.requireNonNull(queueProperties, "queueProperties must not be null");
        this.retryProperties = Objects.requireNonNull(retryProperties, "retryProperties must not be null");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory must not be null");
    }

    /**
     * 현재 retry 횟수에 대응하는 due 시각을 계산하고 PROCESSING ticket을 전환한다.
     * Redis Lua가 증가시킨 횟수와 같은 backoff를 사용하도록 다음 횟수를 Hash에서 읽는다.
     */
    @Override
    public ReservationQueueRetryResult schedule(
            ReservationQueueWorkItem item,
            String workerId,
            String errorCode,
            Instant failedAt
    ) {
        Objects.requireNonNull(item, "item must not be null");
        requireText(workerId, "workerId");
        requireErrorCode(errorCode);
        Objects.requireNonNull(failedAt, "failedAt must not be null");
        Object storedCount = redisTemplate.opsForHash().get(
                keyFactory.ticket(item.performanceTimeId(), item.ticketId()),
                "retryCount"
        );
        int nextAttempt = storedCount == null ? 1 : Integer.parseInt(storedCount.toString()) + 1;
        Duration backoff = retryProperties.backoffFor(nextAttempt);
        String result = redisTemplate.execute(
                SCHEDULE,
                List.of(
                        keyFactory.processing(item.performanceTimeId()),
                        keyFactory.retry(item.performanceTimeId()),
                        keyFactory.admitted(item.performanceTimeId()),
                        keyFactory.deadline(item.performanceTimeId()),
                        keyFactory.ticket(item.performanceTimeId(), item.ticketId())
                ),
                item.ticketId().toString(),
                item.streamId(),
                item.ownerToken().toString(),
                workerId,
                String.valueOf(failedAt.toEpochMilli()),
                String.valueOf(failedAt.plus(backoff).toEpochMilli()),
                String.valueOf(retryProperties.maxAttempts()),
                errorCode,
                EXHAUSTED_CODE,
                String.valueOf(queueProperties.ticketRetention().toMillis())
        );
        return retryResult(result);
    }

    /**
     * Retry ZSET에서 due ticket을 제한된 수만 읽고 ticket별 원자 승격 Lua를 실행한다.
     * 다른 scanner가 먼저 승격한 ticket은 결과 건수에서 제외한다.
     */
    @Override
    public int promoteDue(long performanceTimeId, Instant now, int limit) {
        Objects.requireNonNull(now, "now must not be null");
        if (performanceTimeId <= 0 || limit <= 0) {
            throw new IllegalArgumentException("Promotion identifiers must be positive");
        }
        Set<String> due = redisTemplate.opsForZSet().rangeByScore(
                keyFactory.retry(performanceTimeId),
                Double.NEGATIVE_INFINITY,
                now.toEpochMilli(),
                0,
                limit
        );
        if (due == null || due.isEmpty()) {
            return 0;
        }
        int promoted = 0;
        for (String rawTicketId : due) {
            UUID ticketId;
            try {
                ticketId = UUID.fromString(rawTicketId);
            } catch (IllegalArgumentException exception) {
                throw new ReservationQueueStorageException("Retry index ticket ID is invalid", exception);
            }
            String result = redisTemplate.execute(
                    PROMOTE,
                    List.of(
                            keyFactory.retry(performanceTimeId),
                            keyFactory.waiting(performanceTimeId),
                            keyFactory.stream(performanceTimeId),
                            keyFactory.ticket(performanceTimeId, ticketId)
                    ),
                    rawTicketId,
                    String.valueOf(now.toEpochMilli()),
                    String.valueOf(queueProperties.ticketRetention().toMillis())
            );
            if ("PROMOTED".equals(result)) {
                promoted++;
            } else if (!"MISSING".equals(result) && !"STATE_MISMATCH".equals(result)
                    && !"NOT_DUE".equals(result)) {
                throw new ReservationQueueStorageException("Unexpected retry promotion result: " + result);
            }
        }
        return promoted;
    }

    /**
     * Lua 반환 문자열을 ACK 판단에 사용하는 retry 결과로 변환한다.
     * 알 수 없는 값은 Redis 저장 계약 오류로 처리한다.
     */
    private ReservationQueueRetryResult retryResult(String result) {
        if (result == null) {
            throw new ReservationQueueStorageException("Redis retry scheduling returned no result");
        }
        return switch (result) {
            case "SCHEDULED" -> ReservationQueueRetryResult.SCHEDULED;
            case "EXHAUSTED" -> ReservationQueueRetryResult.EXHAUSTED;
            case "ALREADY_TERMINAL" -> ReservationQueueRetryResult.ALREADY_TERMINAL;
            case "OWNER_MISMATCH" -> ReservationQueueRetryResult.OWNER_MISMATCH;
            case "STATE_MISMATCH" -> ReservationQueueRetryResult.STATE_MISMATCH;
            case "MISSING" -> ReservationQueueRetryResult.MISSING;
            default -> throw new ReservationQueueStorageException("Unexpected retry result: " + result);
        };
    }

    /**
     * Worker identity 문자열이 공백인지 확인한다.
     * 잘못된 owner 값이 retry Lua에 전달되는 것을 막는다.
     */
    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * Retry 원인을 고정된 공개 code 형식으로 제한한다.
     * 동적 예외 메시지가 ticket Hash에 저장되는 것을 막는다.
     */
    private void requireErrorCode(String value) {
        if (value == null || !ERROR_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("errorCode must be a stable public code");
        }
    }

    /**
     * Classpath Lua를 문자열 반환 script로 구성한다.
     * Retry scheduling과 승격 명령이 같은 로딩 계약을 사용하게 한다.
     */
    private static DefaultRedisScript<String> script(String location) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(String.class);
        return script;
    }
}

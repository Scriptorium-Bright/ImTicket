package org.example.ticket.reservation.queue.repository.redis;

import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueMappingRepairResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueTerminalCleanupResult;
import org.example.ticket.reservation.queue.exception.ReservationQueueStorageException;
import org.example.ticket.reservation.queue.repository.ReservationQueueMaintenanceStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Redis index와 Lua로 stale mapping 복구, active 보정과 terminal cleanup을 구현한다. */
public final class RedisReservationQueueMaintenanceStore implements ReservationQueueMaintenanceStore {

    private static final DefaultRedisScript<String> RECONCILE_MAPPING = script(
            "redis/reservation-queue/reservation_queue_reconcile_mapping.lua"
    );
    private static final DefaultRedisScript<String> CLEANUP_TERMINAL = script(
            "redis/reservation-queue/reservation_queue_cleanup_terminal.lua"
    );

    private final StringRedisTemplate redisTemplate;
    private final ReservationQueueProperties properties;
    private final ReservationQueueKeyFactory keyFactory;

    /**
     * Maintenance 명령에 필요한 Redis template, 보존 정책과 key 규칙을 연결한다.
     * Mapping, active와 terminal 정리가 admission과 같은 namespace를 사용하게 한다.
     */
    public RedisReservationQueueMaintenanceStore(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory must not be null");
    }

    /**
     * Stale ENQUEUING index를 batch 조회하고 ticket 존재 여부에 따른 Lua 보정을 실행한다.
     * Field가 손상되거나 ticket과 다른 mapping은 삭제하지 않고 mismatch로 집계한다.
     */
    @Override
    public ReservationQueueMappingRepairResult reconcileStaleMappings(
            Instant staleBefore,
            Instant repairedAt,
            int limit
    ) {
        Objects.requireNonNull(staleBefore, "staleBefore must not be null");
        Objects.requireNonNull(repairedAt, "repairedAt must not be null");
        requirePositive(limit, "limit");
        Set<String> mappings = redisTemplate.opsForZSet().rangeByScore(
                keyFactory.enqueuingMappings(),
                Double.NEGATIVE_INFINITY,
                staleBefore.toEpochMilli(),
                0,
                limit
        );
        if (mappings == null || mappings.isEmpty()) {
            return new ReservationQueueMappingRepairResult(0, 0, 0);
        }
        int repaired = 0;
        int released = 0;
        int mismatched = 0;
        for (String mappingKey : mappings) {
            Map<Object, Object> fields = redisTemplate.opsForHash().entries(mappingKey);
            if (fields.isEmpty()) {
                redisTemplate.opsForZSet().remove(keyFactory.enqueuingMappings(), mappingKey);
                continue;
            }
            String ticketKey = ticketKey(fields);
            if (ticketKey == null) {
                mismatched++;
                continue;
            }
            String result = redisTemplate.execute(
                    RECONCILE_MAPPING,
                    List.of(mappingKey, keyFactory.enqueuingMappings(), ticketKey),
                    String.valueOf(staleBefore.toEpochMilli()),
                    String.valueOf(repairedAt.toEpochMilli()),
                    String.valueOf(properties.idempotencyRetention().toMillis())
            );
            switch (requireResult(result, "mapping reconciliation")) {
                case "REPAIRED" -> repaired++;
                case "RELEASED" -> released++;
                case "MISMATCH" -> mismatched++;
                case "ORPHAN_INDEX", "ALREADY_QUEUED", "NOT_DUE" -> { }
                default -> throw new ReservationQueueStorageException(
                        "Unexpected mapping reconciliation result: " + result
                );
            }
        }
        return new ReservationQueueMappingRepairResult(repaired, released, mismatched);
    }

    /**
     * Admission이 남긴 repair candidate를 제한된 수만 읽어 active performance score를 보정한다.
     * 정상 등록 뒤 후보를 제거해 다음 tick이 실제 누락 건만 처리하게 한다.
     */
    @Override
    public int repairActivePerformances(Instant now, int scanCount) {
        Objects.requireNonNull(now, "now must not be null");
        requirePositive(scanCount, "scanCount");
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> candidates =
                redisTemplate.opsForZSet().rangeWithScores(
                        keyFactory.activeRepairCandidates(),
                        0,
                        scanCount - 1L
                );
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int repaired = 0;
        for (org.springframework.data.redis.core.ZSetOperations.TypedTuple<String> candidate : candidates) {
            String rawPerformanceId = candidate.getValue();
            Double score = candidate.getScore();
            if (rawPerformanceId == null || score == null) {
                continue;
            }
            redisTemplate.opsForZSet().add(
                    keyFactory.activePerformanceTimes(),
                    rawPerformanceId,
                    Math.max(score, now.toEpochMilli())
            );
            redisTemplate.opsForZSet().remove(keyFactory.activeRepairCandidates(), rawPerformanceId);
            repaired++;
        }
        return repaired;
    }

    /**
     * 보존 기준을 지난 terminal ticket을 조회하고 pending이 없는 Stream entry만 제거한다.
     * Pending 확인 뒤 Lua가 ticket 상태와 score를 다시 검사해 cleanup 경쟁을 방어한다.
     */
    @Override
    public ReservationQueueTerminalCleanupResult cleanupTerminalTickets(
            long performanceTimeId,
            String consumerGroup,
            Instant completedBefore,
            int limit
    ) {
        requirePositive(performanceTimeId, "performanceTimeId");
        requireText(consumerGroup, "consumerGroup");
        Objects.requireNonNull(completedBefore, "completedBefore must not be null");
        requirePositive(limit, "limit");
        Set<String> ticketIds = redisTemplate.opsForZSet().rangeByScore(
                keyFactory.terminal(performanceTimeId),
                Double.NEGATIVE_INFINITY,
                completedBefore.toEpochMilli(),
                0,
                limit
        );
        if (ticketIds == null || ticketIds.isEmpty()) {
            return new ReservationQueueTerminalCleanupResult(0, 0);
        }
        int cleaned = 0;
        for (String rawTicketId : ticketIds) {
            UUID ticketId;
            try {
                ticketId = UUID.fromString(rawTicketId);
            } catch (IllegalArgumentException exception) {
                throw new ReservationQueueStorageException("Terminal index ticket ID is invalid", exception);
            }
            String ticketKey = keyFactory.ticket(performanceTimeId, ticketId);
            Object rawStreamId = redisTemplate.opsForHash().get(ticketKey, "streamId");
            if (rawStreamId == null) {
                redisTemplate.opsForZSet().remove(keyFactory.terminal(performanceTimeId), rawTicketId);
                continue;
            }
            String streamId = rawStreamId.toString();
            if (isPending(performanceTimeId, consumerGroup, streamId)) {
                continue;
            }
            String result = redisTemplate.execute(
                    CLEANUP_TERMINAL,
                    List.of(
                            keyFactory.terminal(performanceTimeId),
                            ticketKey,
                            keyFactory.stream(performanceTimeId),
                            keyFactory.admitted(performanceTimeId),
                            keyFactory.waiting(performanceTimeId),
                            keyFactory.processing(performanceTimeId),
                            keyFactory.retry(performanceTimeId),
                            keyFactory.deadline(performanceTimeId)
                    ),
                    rawTicketId,
                    streamId,
                    String.valueOf(completedBefore.toEpochMilli())
            );
            if ("CLEANED".equals(requireResult(result, "terminal cleanup"))) {
                cleaned++;
            } else if (!"ORPHAN_INDEX".equals(result) && !"NOT_DUE".equals(result)
                    && !"NOT_TERMINAL".equals(result) && !"MISMATCH".equals(result)) {
                throw new ReservationQueueStorageException("Unexpected terminal cleanup result: " + result);
            }
        }
        return new ReservationQueueTerminalCleanupResult(ticketIds.size(), cleaned);
    }

    /**
     * Mapping Hash에서 ticket key 생성에 필요한 식별자를 안전하게 복원한다.
     * 누락되거나 형식이 잘못된 mapping은 삭제하지 않도록 null을 반환한다.
     */
    private String ticketKey(Map<Object, Object> fields) {
        try {
            Object rawPerformance = fields.get("performanceTimeId");
            Object rawTicket = fields.get("ticketId");
            if (rawPerformance == null || rawTicket == null) {
                return null;
            }
            return keyFactory.ticket(
                    Long.parseLong(rawPerformance.toString()),
                    UUID.fromString(rawTicket.toString())
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Stream entry가 Consumer Group pending 목록에 남아 있는지 정확한 ID 범위로 확인한다.
     * Group이 아직 없으면 pending 작업이 없는 것으로 해석한다.
     */
    private boolean isPending(long performanceTimeId, String consumerGroup, String streamId) {
        try {
            PendingMessages pending = redisTemplate.opsForStream().pending(
                    keyFactory.stream(performanceTimeId),
                    consumerGroup,
                    Range.closed(streamId, streamId),
                    1
            );
            return pending != null && !pending.isEmpty();
        } catch (DataAccessException exception) {
            if (isNoGroup(exception)) {
                return false;
            }
            throw exception;
        }
    }

    /**
     * Redis 예외 원인 체인에서 NOGROUP 응답을 찾는다.
     * 다른 저장 오류는 cleanup 판단에 사용하지 않고 호출자에게 전달한다.
     */
    private boolean isNoGroup(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("NOGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Redis Lua 결과가 존재하는지 확인한다.
     * null 응답은 상태를 판단할 수 없는 저장 계약 오류로 바꾼다.
     */
    private String requireResult(String result, String operation) {
        if (result == null) {
            throw new ReservationQueueStorageException("Redis " + operation + " returned no result");
        }
        return result;
    }

    /**
     * 문자열 설정이 null 또는 공백인지 확인한다.
     * 잘못된 Consumer Group이 pending 조회에 전달되는 것을 막는다.
     */
    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * 정수와 회차 식별자가 양수인지 확인한다.
     * 무제한 scan이나 잘못된 Redis key 생성을 막는다.
     */
    private void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /**
     * Classpath Lua를 문자열 반환 script로 구성한다.
     * Mapping 보정과 terminal cleanup이 같은 script 로딩 계약을 사용하게 한다.
     */
    private static DefaultRedisScript<String> script(String location) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(String.class);
        return script;
    }
}

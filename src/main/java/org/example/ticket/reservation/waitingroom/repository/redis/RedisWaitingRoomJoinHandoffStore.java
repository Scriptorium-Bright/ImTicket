package org.example.ticket.reservation.waitingroom.repository.redis;

import lombok.RequiredArgsConstructor;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffRequest;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffState;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffSubmission;
import org.example.ticket.reservation.waitingroom.repository.WaitingRoomJoinHandoffStore;
import org.example.ticket.reservation.waitingroom.repository.WaitingRoomJoinHandoffStreamRecord;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomJoinHandoffStatus;
import org.example.ticket.reservation.waitingroom.exception.WaitingRoomStorageException;
import org.example.ticket.reservation.waitingroom.exception.WaitingRoomCapacityException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Redis Stream과 request state를 원자적으로 저장하고 consumer group을 읽는다. */
@Repository
@RequiredArgsConstructor
public class RedisWaitingRoomJoinHandoffStore implements WaitingRoomJoinHandoffStore {

    private static final String CONSUMER_GROUP = "waiting-room-join-workers";
    private static final RedisScript<String> ENQUEUE_SCRIPT = script(
            "redis/waiting-room/waiting_room_join_handoff_enqueue.lua"
    );

    private final StringRedisTemplate redisTemplate;
    private final WaitingRoomKeyFactory keyFactory;
    private final String consumerName = "worker-" + UUID.randomUUID();
    private final Set<String> initializedConsumerGroups = ConcurrentHashMap.newKeySet();

    /** Redis Lua로 owner dedupe와 Stream enqueue를 하나의 원자 구간에서 수행한다.
     * queue가 가득 차면 전용 capacity 예외를 반환한다. */
    @Override
    public WaitingRoomJoinHandoffSubmission enqueue(
            WaitingRoomJoinHandoffRequest request,
            Duration retention,
            int maxQueueLength
    ) {
        String result = execute(
                ENQUEUE_SCRIPT,
                List.of(
                        keyFactory.joinHandoffStream(request.performanceTimeId()),
                        keyFactory.joinHandoffRequest(request.performanceTimeId(), request.requestId()),
                        keyFactory.joinHandoffOwner(request.performanceTimeId(), request.memberId()),
                        keyFactory.sequence(request.performanceTimeId()),
                        keyFactory.waiting(request.performanceTimeId()),
                        keyFactory.deadline(request.performanceTimeId()),
                        keyFactory.ticket(request.performanceTimeId(), request.ticketId()),
                        keyFactory.owner(request.performanceTimeId(), request.memberId())
                ),
                request.requestId().toString(),
                request.ticketId().toString(),
                Long.toString(request.performanceTimeId()),
                Long.toString(request.memberId()),
                Long.toString(request.enqueuedAt().toEpochMilli()),
                Long.toString(request.waitingDeadline().toEpochMilli()),
                Long.toString(retention.toMillis()),
                Integer.toString(maxQueueLength)
        );
        String[] parts = result.split("\\|", -1);
        if ("QUEUE_FULL".equals(result)) {
            throw new WaitingRoomCapacityException();
        }
        if (parts.length >= 2 && "EXISTING".equals(parts[0])) {
            return new WaitingRoomJoinHandoffSubmission(UUID.fromString(parts[1]), null, false);
        }
        if (parts.length >= 3 && "CREATED".equals(parts[0])) {
            return new WaitingRoomJoinHandoffSubmission(UUID.fromString(parts[1]), UUID.fromString(parts[2]), true);
        }
        throw new WaitingRoomStorageException("예상하지 못한 join handoff Redis 결과: " + result);
    }

    /** request Hash를 읽어 비동기 join 상태로 복원한다.
     * 필드 손상과 Redis command 오류는 storage 예외로 변환한다. */
    @Override
    public Optional<WaitingRoomJoinHandoffState> find(long performanceTimeId, UUID requestId) {
        try {
            Map<Object, Object> values = redisTemplate.opsForHash().entries(
                    keyFactory.joinHandoffRequest(performanceTimeId, requestId)
            );
            if (values.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new WaitingRoomJoinHandoffState(
                    UUID.fromString(value(values, "requestId")),
                    Long.parseLong(value(values, "performanceTimeId")),
                    Long.parseLong(value(values, "memberId")),
                    WaitingRoomJoinHandoffStatus.valueOf(value(values, "status")),
                    Instant.ofEpochMilli(Long.parseLong(value(values, "enqueuedAt"))),
                    optionalUuid(values.get("ticketId")),
                    optionalString(values.get("errorCode")),
                    Boolean.parseBoolean(valueOrDefault(values, "retryable", "false"))
            ));
        } catch (RuntimeException exception) {
            throw storageFailure("join handoff state 조회에 실패했습니다.", exception);
        }
    }

    /** request를 worker 처리 중 상태로 갱신한다.
     * 갱신 뒤에도 request 보존 TTL을 유지한다. */
    @Override
    public void markProcessing(long performanceTimeId, UUID requestId, Duration retention) {
        updateState(performanceTimeId, requestId, retention, Map.of(
                "status", WaitingRoomJoinHandoffStatus.PROCESSING.name(),
                "retryable", "false"
        ));
    }

    /** ticket ID를 기록하고 request를 완료 상태로 갱신한다.
     * frontend가 request SSE 재연결 뒤 ticket 상태를 조회할 수 있다. */
    @Override
    public void markCompleted(long performanceTimeId, UUID requestId, UUID ticketId, Duration retention) {
        updateState(performanceTimeId, requestId, retention, Map.of(
                "status", WaitingRoomJoinHandoffStatus.COMPLETED.name(),
                "ticketId", ticketId.toString(),
                "retryable", "false"
        ));
    }

    /** 실패 code와 재시도 가능 여부를 request Hash에 저장한다.
     * 실패 상태도 설정된 retention 동안 조회할 수 있다. */
    @Override
    public void markFailed(long performanceTimeId, UUID requestId, String errorCode, boolean retryable, Duration retention) {
        updateState(performanceTimeId, requestId, retention, Map.of(
                "status", WaitingRoomJoinHandoffStatus.FAILED.name(),
                "errorCode", errorCode,
                "retryable", Boolean.toString(retryable)
        ));
    }

    /** Redis Stream consumer group을 회차별로 생성한다.
     * 이미 존재하는 group은 BUSYGROUP 결과를 정상 상태로 처리한다. */
    @Override
    public void ensureConsumerGroup(long performanceTimeId) {
        String stream = keyFactory.joinHandoffStream(performanceTimeId);
        if (initializedConsumerGroups.contains(stream)) {
            return;
        }
        try {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(stream))) {
                redisTemplate.opsForStream().add(stream, Map.of("bootstrap", "true"));
            }
            redisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0-0"), CONSUMER_GROUP);
            initializedConsumerGroups.add(stream);
        } catch (RuntimeException exception) {
            if (isBusyGroup(exception)) {
                initializedConsumerGroups.add(stream);
                return;
            }
            throw storageFailure("join handoff consumer group 생성에 실패했습니다.", exception);
        }
    }

    /** Redis consumer group이 이미 존재한다는 원인인지 확인한다.
     * Spring Data Redis가 원인 예외를 여러 단계로 감쌀 수 있어 cause chain을 순회한다. */
    private boolean isBusyGroup(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (String.valueOf(current.getMessage()).contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** consumer group의 다음 entry를 읽어 domain request로 변환한다.
     * bootstrap entry는 acknowledge하고 사용자 request로 반환하지 않는다. */
    @Override
    public Optional<WaitingRoomJoinHandoffStreamRecord> readNext(long performanceTimeId) {
        return readNextBatch(performanceTimeId, 1).stream().findFirst();
    }

    /** consumer group에서 여러 entry를 한 번에 읽어 request로 변환한다.
     * Redis Stream의 한 번의 read로 처리자 동시성만큼 작업을 확보한다. */
    @Override
    public List<WaitingRoomJoinHandoffStreamRecord> readNextBatch(long performanceTimeId, int count) {
        if (count <= 0) {
            return List.of();
        }
        String stream = keyFactory.joinHandoffStream(performanceTimeId);
        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                    Consumer.from(CONSUMER_GROUP, consumerName),
                    org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(count),
                    StreamOffset.create(stream, ReadOffset.lastConsumed())
            );
            return requestRecords(stream, records);
        } catch (RuntimeException exception) {
            forgetConsumerGroupWhenMissing(stream, exception);
            throw storageFailure("join handoff stream 읽기에 실패했습니다.", exception);
        }
    }

    /** idle pending entry를 claim해 worker 재시작 후 처리를 재개한다.
     * 지정한 idle 시간보다 짧은 entry는 현재 consumer에게 유지한다. */
    @Override
    public Optional<WaitingRoomJoinHandoffStreamRecord> claimIdle(long performanceTimeId, Duration idleAfter) {
        return claimIdleBatch(performanceTimeId, idleAfter, 1).stream().findFirst();
    }

    /** idle pending entry를 batch로 claim해 처리자 재시작을 복구한다.
     * 한 실행에서 bounded count만 회수해 Redis와 executor 폭주를 막는다. */
    @Override
    public List<WaitingRoomJoinHandoffStreamRecord> claimIdleBatch(
            long performanceTimeId,
            Duration idleAfter,
            int count
    ) {
        if (count <= 0) {
            return List.of();
        }
        String stream = keyFactory.joinHandoffStream(performanceTimeId);
        try {
            PendingMessages pending = redisTemplate.opsForStream().pending(
                    stream,
                    CONSUMER_GROUP,
                    Range.unbounded(),
                    count
            );
            java.util.ArrayList<WaitingRoomJoinHandoffStreamRecord> recovered = new java.util.ArrayList<>();
            for (PendingMessage message : pending) {
                if (recovered.size() >= count) {
                    break;
                }
                if (message.getElapsedTimeSinceLastDelivery().compareTo(idleAfter) < 0) {
                    continue;
                }
                List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream().claim(
                        stream,
                        CONSUMER_GROUP,
                        consumerName,
                        idleAfter,
                        message.getId()
                );
                recovered.addAll(requestRecords(stream, claimed));
            }
            return recovered;
        } catch (RuntimeException exception) {
            forgetConsumerGroupWhenMissing(stream, exception);
            throw storageFailure("join handoff pending entry 복구에 실패했습니다.", exception);
        }
    }

    /** 처리된 Stream entry를 consumer group에 acknowledge한다.
     * Redis 오류는 storage 예외로 전달한다. */
    @Override
    public void acknowledge(long performanceTimeId, String streamRecordId) {
        String stream = keyFactory.joinHandoffStream(performanceTimeId);
        try {
            redisTemplate.opsForStream().acknowledge(
                    stream,
                    CONSUMER_GROUP,
                    streamRecordId
            );
        } catch (RuntimeException exception) {
            forgetConsumerGroupWhenMissing(stream, exception);
            throw storageFailure("join handoff stream acknowledge에 실패했습니다.", exception);
        }
    }

    /**
     * Redis 재시작이나 key 만료로 consumer group이 사라지면 다음 poll에서 재생성한다.
     * 초기화 집합에서 해당 Stream을 제거해 재생성을 허용한다.
     */
    private void forgetConsumerGroupWhenMissing(String stream, Throwable throwable) {
        if (containsRedisError(throwable, "NOGROUP")) {
            initializedConsumerGroups.remove(stream);
        }
    }

    /**
     * Redis 오류 code가 cause chain에 포함되어 있는지 확인한다.
     * 중첩된 원인 예외의 메시지까지 순서대로 검사한다.
     */
    private boolean containsRedisError(Throwable throwable, String errorCode) {
        Throwable current = throwable;
        while (current != null) {
            if (String.valueOf(current.getMessage()).contains(errorCode)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** Stream record의 field를 request 객체로 변환한다.
     * bootstrap record는 즉시 acknowledge하고 빈 결과를 반환한다. */
    private List<WaitingRoomJoinHandoffStreamRecord> requestRecords(
            String stream,
            List<MapRecord<String, Object, Object>> records
    ) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<WaitingRoomJoinHandoffStreamRecord> requests = new java.util.ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            Object requestId = record.getValue().get("requestId");
            if (requestId == null) {
                acknowledgeStream(stream, record.getId().getValue());
                continue;
            }
            Map<Object, Object> values = record.getValue().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            requests.add(new WaitingRoomJoinHandoffStreamRecord(
                    record.getId().getValue(),
                    new WaitingRoomJoinHandoffRequest(
                            UUID.fromString(requestId.toString()),
                            UUID.fromString(values.get("ticketId").toString()),
                            Long.parseLong(values.get("performanceTimeId").toString()),
                            Long.parseLong(values.get("memberId").toString()),
                            Instant.ofEpochMilli(Long.parseLong(values.get("enqueuedAt").toString())),
                            Instant.ofEpochMilli(Long.parseLong(values.get("waitingDeadline").toString()))
                    )
            ));
        }
        return requests;
    }

    /** bootstrap Stream record를 consumer group에서 acknowledge한다.
     * 해당 record가 다음 poll에서 반복되지 않도록 정리한다. */
    private void acknowledgeStream(String stream, String streamRecordId) {
        redisTemplate.opsForStream().acknowledge(stream, CONSUMER_GROUP, streamRecordId);
    }

    /** request Hash field와 TTL을 함께 갱신한다.
     * 상태 변경이 실패하면 worker가 pending entry 복구 경로를 사용한다. */
    private void updateState(long performanceTimeId, UUID requestId, Duration retention, Map<String, String> values) {
        try {
            redisTemplate.opsForHash().putAll(keyFactory.joinHandoffRequest(performanceTimeId, requestId), values);
            redisTemplate.expire(keyFactory.joinHandoffRequest(performanceTimeId, requestId), retention);
        } catch (RuntimeException exception) {
            throw storageFailure("join handoff state 갱신에 실패했습니다.", exception);
        }
    }

    /** Redis Lua script를 실행하고 빈 결과를 오류로 처리한다.
     * infrastructure 예외는 WaitingRoomStorageException으로 표준화한다. */
    private String execute(RedisScript<String> script, List<String> keys, String... args) {
        try {
            String result = redisTemplate.execute(script, keys, (Object[]) args);
            if (result == null) {
                throw new WaitingRoomStorageException("join handoff script가 빈 결과를 반환했습니다.");
            }
            return result;
        } catch (WaitingRoomStorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw storageFailure("join handoff enqueue에 실패했습니다.", exception);
        }
    }

    /** Hash field를 필수 문자열로 읽는다.
     * field가 없으면 손상된 request state로 처리한다. */
    private String value(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalStateException("missing join handoff field: " + field);
        }
        return value.toString();
    }

    /** Hash field를 읽고 없으면 기본 문자열을 반환한다.
     * retryable처럼 기존 데이터에 없을 수 있는 field에 사용한다. */
    private String valueOrDefault(Map<Object, Object> values, String field, String defaultValue) {
        Object value = values.get(field);
        return value == null ? defaultValue : value.toString();
    }

    /** nullable Hash field를 UUID로 변환한다.
     * 완료 전 request에는 ticket ID가 없을 수 있다. */
    private UUID optionalUuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    /** nullable Hash field를 문자열로 변환한다.
     * 실패 전 request에는 error code가 없을 수 있다. */
    private String optionalString(Object value) {
        return value == null ? null : value.toString();
    }

    /** Redis command 원인과 작업 설명을 storage 예외로 결합한다.
     * 상위 service가 API 오류 code를 선택할 수 있게 한다. */
    private WaitingRoomStorageException storageFailure(String message, RuntimeException cause) {
        return new WaitingRoomStorageException(message, cause);
    }

    /** classpath Lua resource를 반환형이 String인 RedisScript로 구성한다.
     * RedisTemplate 실행 시 script result contract를 고정한다. */
    private static RedisScript<String> script(String classpath) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpath));
        script.setResultType(String.class);
        return script;
    }
}

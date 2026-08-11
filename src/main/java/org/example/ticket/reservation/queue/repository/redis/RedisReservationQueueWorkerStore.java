package org.example.ticket.reservation.queue.repository.redis;

import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueClaimResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueStreamMessage;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;
import org.example.ticket.reservation.queue.repository.ReservationQueueWorkerStore;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Redis Consumer Group 읽기, PROCESSING claim과 ACK를 구현하는 Worker 저장소다. */
public final class RedisReservationQueueWorkerStore implements ReservationQueueWorkerStore {

    private final StringRedisTemplate redisTemplate;
    private final ReservationQueueKeyFactory keyFactory;
    private final ReservationQueueWorkerRedisCommands redisCommands;

    /**
     * Worker Stream과 claim에 필요한 Redis template, Queue 설정과 key factory를 연결한다.
     * Claim 명령은 별도 Lua command 객체에 위임해 상태 해석을 한곳에 둔다.
     */
    public RedisReservationQueueWorkerStore(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties queueProperties,
            ReservationQueueKeyFactory keyFactory
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory must not be null");
        this.redisCommands = new ReservationQueueWorkerRedisCommands(
                redisTemplate,
                queueProperties,
                keyFactory
        );
    }

    /**
     * `XGROUP CREATE ... MKSTREAM`으로 회차 Consumer Group을 생성한다.
     * 동시 생성에서 발생한 BUSYGROUP은 이미 목표 상태이므로 성공으로 처리한다.
     */
    @Override
    public void ensureConsumerGroup(long performanceTimeId, String consumerGroup) {
        requireText(consumerGroup, "consumerGroup");
        String streamKey = keyFactory.stream(performanceTimeId);
        byte[] rawKey = Objects.requireNonNull(
                redisTemplate.getStringSerializer().serialize(streamKey),
                "serialized stream key must not be null"
        );
        try {
            redisTemplate.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            rawKey,
                            consumerGroup,
                            ReadOffset.from("0-0"),
                            true
                    ));
        } catch (DataAccessException exception) {
            if (!isBusyGroup(exception)) {
                throw exception;
            }
        }
    }

    /**
     * `XREADGROUP COUNT 1 BLOCK`으로 새 entry 한 건을 읽는다.
     * Redis record의 key와 value를 문자열 map으로 바꿔 decoder에 전달한다.
     */
    @Override
    public Optional<ReservationQueueStreamMessage> readNew(
            long performanceTimeId,
            String consumerGroup,
            String consumerName,
            Duration blockTimeout
    ) {
        requireText(consumerGroup, "consumerGroup");
        requireText(consumerName, "consumerName");
        requirePositive(blockTimeout, "blockTimeout");
        String streamKey = keyFactory.stream(performanceTimeId);
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(consumerGroup, consumerName),
                StreamReadOptions.empty().count(1).block(blockTimeout),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        );
        if (records == null || records.isEmpty()) {
            return Optional.empty();
        }
        MapRecord<String, Object, Object> record = records.getFirst();
        Map<String, String> fields = new LinkedHashMap<>();
        record.getValue().forEach((key, value) -> fields.put(String.valueOf(key), String.valueOf(value)));
        return Optional.of(new ReservationQueueStreamMessage(
                performanceTimeId,
                record.getId().getValue(),
                fields
        ));
    }

    /**
     * 검증된 Worker item의 WAITING ticket을 PROCESSING으로 claim한다.
     * 실제 원자 상태 변경과 owner 검증은 Lua command에 위임한다.
     */
    @Override
    public ReservationQueueClaimResult claim(
            ReservationQueueWorkItem item,
            String workerId,
            Instant claimedAt,
            Duration processingLease
    ) {
        Objects.requireNonNull(item, "item must not be null");
        requireText(workerId, "workerId");
        Objects.requireNonNull(claimedAt, "claimedAt must not be null");
        requirePositive(processingLease, "processingLease");
        return redisCommands.claim(item, workerId, claimedAt, processingLease);
    }

    /**
     * 검증된 item의 Stream ID를 Consumer Group pending 목록에서 ACK한다.
     * terminal 저장 이후 호출자가 처리한 실제 ACK 수를 반환한다.
     */
    @Override
    public long acknowledge(ReservationQueueWorkItem item, String consumerGroup) {
        Objects.requireNonNull(item, "item must not be null");
        return acknowledge(item.performanceTimeId(), item.streamId(), consumerGroup);
    }

    /**
     * payload 복원에 실패한 원본 message의 Stream ID를 ACK한다.
     * ticket과 연결할 수 없는 poison entry의 반복 전달을 종료할 때 사용한다.
     */
    @Override
    public long acknowledge(ReservationQueueStreamMessage message, String consumerGroup) {
        Objects.requireNonNull(message, "message must not be null");
        return acknowledge(message.performanceTimeId(), message.streamId(), consumerGroup);
    }

    /**
     * 회차 Stream, group과 entry ID로 실제 XACK를 실행한다.
     * Redis가 null을 반환하면 ACK된 entry가 없는 것으로 해석한다.
     */
    private long acknowledge(long performanceTimeId, String streamId, String consumerGroup) {
        requireText(streamId, "streamId");
        requireText(consumerGroup, "consumerGroup");
        Long acknowledged = redisTemplate.opsForStream().acknowledge(
                keyFactory.stream(performanceTimeId),
                consumerGroup,
                streamId
        );
        return acknowledged == null ? 0L : acknowledged;
    }

    /**
     * 예외 원인 체인에서 Redis BUSYGROUP 응답을 찾는다.
     * group 생성 경쟁 외의 Redis 오류는 호출자에게 그대로 전달한다.
     */
    private boolean isBusyGroup(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Consumer Group과 Worker ID 문자열이 공백인지 확인한다.
     * 잘못된 identity가 Redis 명령에 전달되는 것을 막는다.
     */
    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * blocking 시간과 processing lease가 양수인지 확인한다.
     * busy loop나 즉시 만료되는 Worker 명령을 생성하지 않게 한다.
     */
    private void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

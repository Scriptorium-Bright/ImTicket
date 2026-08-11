package org.example.ticket.reservation.queue.repository.redis;

import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueClaimResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;
import org.example.ticket.reservation.queue.exception.ReservationQueueStorageException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Worker PROCESSING claim Lua의 key와 argument, 반환값 해석을 담당한다. */
final class ReservationQueueWorkerRedisCommands {

    private static final DefaultRedisScript<String> CLAIM_PROCESSING = script(
            "redis/reservation-queue/reservation_queue_claim_processing.lua"
    );

    private final StringRedisTemplate redisTemplate;
    private final ReservationQueueProperties queueProperties;
    private final ReservationQueueKeyFactory keyFactory;

    /**
     * PROCESSING claim에 필요한 Redis template, 보존 설정과 key 규칙을 연결한다.
     * 모든 Worker claim이 admission과 같은 회차 hash tag를 사용하게 한다.
     */
    ReservationQueueWorkerRedisCommands(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties queueProperties,
            ReservationQueueKeyFactory keyFactory
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.queueProperties = Objects.requireNonNull(queueProperties, "queueProperties must not be null");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory must not be null");
    }

    /**
     * ticket field와 Stream 입력이 일치할 때 WAITING을 PROCESSING으로 원자 전환한다.
     * Worker ID와 lease 만료 시각을 Hash와 processing ZSET에 함께 기록한다.
     */
    ReservationQueueClaimResult claim(
            ReservationQueueWorkItem item,
            String workerId,
            Instant claimedAt,
            Duration processingLease
    ) {
        String result = redisTemplate.execute(
                CLAIM_PROCESSING,
                List.of(
                        keyFactory.waiting(item.performanceTimeId()),
                        keyFactory.processing(item.performanceTimeId()),
                        keyFactory.ticket(item.performanceTimeId(), item.ticketId())
                ),
                item.ticketId().toString(),
                item.streamId(),
                item.ownerToken().toString(),
                workerId,
                String.valueOf(item.payload().schemaVersion()),
                item.payload().requestHash(),
                item.payload().idempotencyKey().hash(),
                String.valueOf(claimedAt.toEpochMilli()),
                String.valueOf(claimedAt.plus(processingLease).toEpochMilli()),
                String.valueOf(queueProperties.ticketRetention().toMillis())
        );
        if (result == null) {
            throw new ReservationQueueStorageException("Redis Worker claim returned no result");
        }
        return switch (result) {
            case "CLAIMED" -> ReservationQueueClaimResult.CLAIMED;
            case "ALREADY_OWNED" -> ReservationQueueClaimResult.ALREADY_OWNED;
            case "MISSING" -> ReservationQueueClaimResult.MISSING;
            case "NOT_WAITING" -> ReservationQueueClaimResult.NOT_WAITING;
            case "PAYLOAD_MISMATCH" -> ReservationQueueClaimResult.PAYLOAD_MISMATCH;
            default -> throw new ReservationQueueStorageException(
                    "Unexpected Redis Worker claim result: " + result
            );
        };
    }

    /**
     * classpath Lua 파일을 문자열 반환 script로 구성한다.
     * 정적 script 정의가 애플리케이션 시작 시 같은 리소스 계약을 사용하게 한다.
     */
    private static DefaultRedisScript<String> script(String location) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(String.class);
        return script;
    }
}

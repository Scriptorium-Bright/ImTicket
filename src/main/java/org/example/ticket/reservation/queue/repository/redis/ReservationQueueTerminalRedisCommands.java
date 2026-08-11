package org.example.ticket.reservation.queue.repository.redis;

import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueStreamMessage;
import org.example.ticket.reservation.queue.dto.ReservationQueueSuccessResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueTerminalResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;
import org.example.ticket.reservation.queue.exception.ReservationQueueStorageException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Queue terminal Lua 명령의 공통 key, argument와 반환값 해석을 담당한다. */
final class ReservationQueueTerminalRedisCommands {

    private static final int FAILURE_SCHEMA_VERSION = 1;
    private static final DefaultRedisScript<String> COMPLETE_SUCCESS = script(
            "redis/reservation-queue/reservation_queue_complete_success.lua"
    );
    private static final DefaultRedisScript<String> COMPLETE_FINAL = script(
            "redis/reservation-queue/reservation_queue_complete_final.lua"
    );
    private static final DefaultRedisScript<String> FAIL_INVALID = script(
            "redis/reservation-queue/reservation_queue_fail_invalid.lua"
    );

    private final StringRedisTemplate redisTemplate;
    private final ReservationQueueProperties properties;
    private final ReservationQueueKeyFactory keyFactory;

    /**
     * Terminal Lua에 필요한 Redis template, 결과 보존 시간과 key 규칙을 연결한다.
     * 성공과 실패 명령이 같은 index 정리 범위를 사용하게 한다.
     */
    ReservationQueueTerminalRedisCommands(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory must not be null");
    }

    /**
     * PROCESSING ticket에 versioned 예약 성공 결과를 저장한다.
     * Worker owner와 Stream ID가 일치하는 경우에만 Queue index를 정리한다.
     */
    ReservationQueueTerminalResult completeSuccess(
            ReservationQueueWorkItem item,
            String workerId,
            ReservationQueueSuccessResult result,
            Instant completedAt
    ) {
        String raw = execute(
                COMPLETE_SUCCESS,
                keys(item.performanceTimeId(), item.ticketId()),
                item.ticketId().toString(),
                item.streamId(),
                item.ownerToken().toString(),
                workerId,
                String.valueOf(completedAt.toEpochMilli()),
                String.valueOf(properties.idempotencyRetention().plus(properties.ticketRetention()).toMillis()),
                String.valueOf(result.schemaVersion()),
                String.valueOf(result.reservationId()),
                String.valueOf(result.totalPrice()),
                result.orderUid(),
                result.expiredTime().toString()
        );
        return terminalResult(raw);
    }

    /**
     * PROCESSING ticket에 공개 오류 code와 failure schema를 저장한다.
     * 내부 예외 메시지는 Lua argument에 포함하지 않는다.
     */
    ReservationQueueTerminalResult completeFinal(
            ReservationQueueWorkItem item,
            String workerId,
            String errorCode,
            Instant completedAt
    ) {
        String raw = execute(
                COMPLETE_FINAL,
                keys(item.performanceTimeId(), item.ticketId()),
                item.ticketId().toString(),
                item.streamId(),
                item.ownerToken().toString(),
                workerId,
                String.valueOf(completedAt.toEpochMilli()),
                String.valueOf(properties.idempotencyRetention().plus(properties.ticketRetention()).toMillis()),
                String.valueOf(FAILURE_SCHEMA_VERSION),
                errorCode
        );
        return terminalResult(raw);
    }

    /**
     * Decoder에서 거절된 WAITING ticket을 공개 final 상태로 전환한다.
     * owner token과 Stream ID를 확인해 다른 ticket을 잘못 닫는 것을 막는다.
     */
    ReservationQueueTerminalResult failInvalid(
            ReservationQueueStreamMessage message,
            UUID ticketId,
            UUID ownerToken,
            String errorCode,
            Instant completedAt
    ) {
        String raw = execute(
                FAIL_INVALID,
                keys(message.performanceTimeId(), ticketId),
                ticketId.toString(),
                message.streamId(),
                ownerToken.toString(),
                String.valueOf(completedAt.toEpochMilli()),
                String.valueOf(properties.idempotencyRetention().plus(properties.ticketRetention()).toMillis()),
                String.valueOf(FAILURE_SCHEMA_VERSION),
                errorCode
        );
        return terminalResult(raw);
    }

    /**
     * Terminal 전이가 함께 정리할 다섯 ZSET과 ticket Hash key를 만든다.
     * 모두 같은 회차 hash tag를 사용해 한 Lua에서 원자 변경된다.
     */
    private List<String> keys(long performanceTimeId, UUID ticketId) {
        return List.of(
                keyFactory.admitted(performanceTimeId),
                keyFactory.waiting(performanceTimeId),
                keyFactory.processing(performanceTimeId),
                keyFactory.retry(performanceTimeId),
                keyFactory.deadline(performanceTimeId),
                keyFactory.terminal(performanceTimeId),
                keyFactory.activeRepairCandidates(),
                keyFactory.ticket(performanceTimeId, ticketId)
        );
    }

    /**
     * Lua script를 실행하고 null 반환을 저장소 오류로 변환한다.
     * 상태 문자열 해석 전에 Redis 응답 존재 여부를 보장한다.
     */
    private String execute(DefaultRedisScript<String> script, List<String> keys, String... arguments) {
        String result = redisTemplate.execute(script, keys, (Object[]) arguments);
        if (result == null) {
            throw new ReservationQueueStorageException("Redis terminal script returned no result");
        }
        return result;
    }

    /**
     * Lua 반환 문자열을 ACK 판단에 사용하는 terminal 결과로 변환한다.
     * key type 오류와 알 수 없는 응답은 저장소 계약 위반으로 처리한다.
     */
    private ReservationQueueTerminalResult terminalResult(String result) {
        return switch (result) {
            case "COMPLETED" -> ReservationQueueTerminalResult.COMPLETED;
            case "ALREADY_TERMINAL" -> ReservationQueueTerminalResult.ALREADY_TERMINAL;
            case "OWNER_MISMATCH" -> ReservationQueueTerminalResult.OWNER_MISMATCH;
            case "MISSING" -> ReservationQueueTerminalResult.MISSING;
            case "INVALID_STATE" -> ReservationQueueTerminalResult.INVALID_STATE;
            case "PAYLOAD_MISMATCH" -> ReservationQueueTerminalResult.PAYLOAD_MISMATCH;
            default -> throw new ReservationQueueStorageException(
                    "Unexpected Redis terminal result: " + result
            );
        };
    }

    /**
     * classpath Lua 파일을 문자열 반환 script로 구성한다.
     * 세 terminal 명령이 동일한 script 로딩 계약을 사용하게 한다.
     */
    private static DefaultRedisScript<String> script(String location) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(String.class);
        return script;
    }
}

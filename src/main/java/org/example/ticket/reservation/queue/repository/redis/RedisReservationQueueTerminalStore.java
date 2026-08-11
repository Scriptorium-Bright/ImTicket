package org.example.ticket.reservation.queue.repository.redis;

import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueStreamMessage;
import org.example.ticket.reservation.queue.dto.ReservationQueueSuccessResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueTerminalResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;
import org.example.ticket.reservation.queue.repository.ReservationQueueTerminalStore;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Redis Lua로 Queue 성공, 공개 final과 invalid payload terminal 전이를 구현한다. */
public final class RedisReservationQueueTerminalStore implements ReservationQueueTerminalStore {

    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z0-9_]{1,64}");
    private final ReservationQueueTerminalRedisCommands redisCommands;

    /**
     * Terminal Lua 실행에 필요한 Redis template, 보존 설정과 key factory를 연결한다.
     * 세 terminal 경로의 상태 해석은 공통 command 객체에 위임한다.
     */
    public RedisReservationQueueTerminalStore(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory
    ) {
        this.redisCommands = new ReservationQueueTerminalRedisCommands(
                redisTemplate,
                properties,
                keyFactory
        );
    }

    /**
     * 검증된 성공 결과를 owner가 일치하는 PROCESSING ticket에 저장한다.
     * 모든 입력을 확인한 뒤 공통 terminal Lua command로 전달한다.
     */
    @Override
    public ReservationQueueTerminalResult completeSuccess(
            ReservationQueueWorkItem item,
            String workerId,
            ReservationQueueSuccessResult result,
            Instant completedAt
    ) {
        Objects.requireNonNull(item, "item must not be null");
        requireText(workerId, "workerId");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        return redisCommands.completeSuccess(item, workerId, result, completedAt);
    }

    /**
     * 공개 final 오류를 owner가 일치하는 PROCESSING ticket에 저장한다.
     * 오류 code 형식을 제한해 내부 예외 메시지가 Hash에 들어가는 것을 막는다.
     */
    @Override
    public ReservationQueueTerminalResult completeFinal(
            ReservationQueueWorkItem item,
            String workerId,
            String errorCode,
            Instant completedAt
    ) {
        Objects.requireNonNull(item, "item must not be null");
        requireText(workerId, "workerId");
        requireErrorCode(errorCode);
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        return redisCommands.completeFinal(item, workerId, errorCode, completedAt);
    }

    /**
     * 원본 Stream field에서 ticket과 owner token을 복원해 invalid final을 시도한다.
     * 두 식별자를 복원할 수 없는 poison entry는 UNLINKED로 분류한다.
     */
    @Override
    public ReservationQueueTerminalResult failInvalid(
            ReservationQueueStreamMessage message,
            String errorCode,
            Instant completedAt
    ) {
        Objects.requireNonNull(message, "message must not be null");
        requireErrorCode(errorCode);
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        Map<String, String> fields = message.fields();
        UUID ticketId;
        UUID ownerToken;
        try {
            ticketId = UUID.fromString(fields.get("ticketId"));
            ownerToken = UUID.fromString(fields.get("ownerToken"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return ReservationQueueTerminalResult.UNLINKED;
        }
        return redisCommands.failInvalid(message, ticketId, ownerToken, errorCode, completedAt);
    }

    /**
     * Worker identity가 공백이 없는지 확인한다.
     * 잘못된 owner 값이 terminal Lua에 전달되는 것을 막는다.
     */
    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * 공개 오류 code를 대문자, 숫자와 밑줄 64자로 제한한다.
     * 예외 message와 동적 문자열이 Redis 결과에 저장되는 것을 차단한다.
     */
    private void requireErrorCode(String errorCode) {
        if (errorCode == null || !ERROR_CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("errorCode must be a stable public code");
        }
    }
}

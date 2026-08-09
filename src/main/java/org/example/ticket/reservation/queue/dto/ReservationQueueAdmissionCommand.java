package org.example.ticket.reservation.queue.dto;

import org.example.ticket.reservation.queue.config.ReservationQueueProperties;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Redis queue admission에 필요한 정규화된 입력이다. */
public record ReservationQueueAdmissionCommand(
        long performanceTimeId,
        UUID ticketId,
        String ownerHash,
        ReservationQueuePayload payload,
        Instant enqueuedAt
) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * Redis admission에 필요한 회차, ticket, 소유자와 payload를 검증한다.
     * Lua 호출 전에 잘못된 식별자와 시간 값이 저장소로 넘어가는 것을 막는다.
     */
    public ReservationQueueAdmissionCommand {
        if (performanceTimeId <= 0) {
            throw new IllegalArgumentException("performanceTimeId must be positive");
        }
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        requireSha256(ownerHash, "ownerHash");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt must not be null");
    }

    /**
     * 접수 시각에 Queue 최대 대기 시간을 더해 만료 시각을 계산한다.
     * ticket Hash와 deadline ZSET이 같은 기준 시각을 사용하게 한다.
     */
    public Instant deadline(ReservationQueueProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        return enqueuedAt.plus(properties.maxWait());
    }

    /**
     * 식별자 hash가 소문자 SHA-256 형식인지 확인한다.
     * 원문 소유자 정보가 admission command에 들어오는 상황을 차단한다.
     */
    private static void requireSha256(String value, String name) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
    }
}

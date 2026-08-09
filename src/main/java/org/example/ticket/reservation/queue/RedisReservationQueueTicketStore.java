package org.example.ticket.reservation.queue;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;
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

    public RedisReservationQueueTicketStore(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory must not be null");
    }

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
            Long position = waitingPosition(performanceTimeId, ticketId, status);
            return Optional.of(new ReservationQueueTicketSnapshot(
                    storedTicketId,
                    storedPerformanceTimeId,
                    required(fields, "ownerHash"),
                    status,
                    Long.parseLong(required(fields, "sequence")),
                    position,
                    Instant.ofEpochMilli(Long.parseLong(required(fields, "enqueuedAt"))),
                    Instant.ofEpochMilli(Long.parseLong(required(fields, "deadlineAt")))
            ));
        } catch (ReservationQueueStorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ReservationQueueStorageException("Queue ticket Hash is invalid", exception);
        }
    }

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

    private String required(Map<Object, Object> fields, String name) {
        Object value = fields.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new ReservationQueueStorageException("Queue ticket field is missing: " + name);
        }
        return value.toString();
    }

    private static DefaultRedisScript<String> script(String location) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(String.class);
        return script;
    }
}

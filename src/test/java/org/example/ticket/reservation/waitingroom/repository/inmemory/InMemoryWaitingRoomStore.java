package org.example.ticket.reservation.waitingroom.repository.inmemory;

import org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinResult;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomPromotionResult;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomTicketSnapshot;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomTicketTransition;
import org.example.ticket.reservation.waitingroom.exception.WaitingRoomCapacityException;
import org.example.ticket.reservation.waitingroom.exception.WaitingRoomStorageException;
import org.example.ticket.reservation.waitingroom.repository.WaitingRoomStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Redis Waiting Room contract를 한 JVM 안에서 비교하기 위한 테스트 전용 저장소다.
 *
 * <p>애플리케이션 빈으로 등록하지 않는다. 회차별 lock이 join·promotion·transition에서
 * 여러 메모리 자료구조를 함께 변경해 Redis Lua 전이와 같은 원자성 경계를 만든다.</p>
 */
public final class InMemoryWaitingRoomStore implements WaitingRoomStore {

    private final ConcurrentHashMap<Long, RoomState> rooms = new ConcurrentHashMap<>();

    /** 회원·회차 ticket을 생성하거나 기존 owner mapping을 반환한다. */
    @Override
    public WaitingRoomJoinResult join(
            long performanceTimeId,
            long memberId,
            UUID ticketId,
            Instant enqueuedAt,
            Instant waitingDeadline,
            Duration storageRetention,
            int maxWaitingTickets
    ) {
        requirePositive(performanceTimeId, "performanceTimeId");
        requirePositive(memberId, "memberId");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt must not be null");
        Objects.requireNonNull(waitingDeadline, "waitingDeadline must not be null");
        requirePositiveDuration(storageRetention, "storageRetention");
        requirePositive(maxWaitingTickets, "maxWaitingTickets");

        RoomState room = room(performanceTimeId);
        room.lock.lock();
        try {
            UUID existingTicketId = room.ownerToTicket.get(memberId);
            if (existingTicketId != null) {
                return new WaitingRoomJoinResult(false, existingTicketId, -1L);
            }
            if (room.tickets.containsKey(ticketId)) {
                throw new WaitingRoomStorageException("ticket ID가 이미 사용 중입니다.");
            }
            if (room.waiting.size() >= maxWaitingTickets) {
                throw new WaitingRoomCapacityException();
            }

            long sequence = room.sequence.incrementAndGet();
            TicketState ticket = new TicketState(
                    ticketId,
                    memberId,
                    performanceTimeId,
                    sequence,
                    enqueuedAt,
                    waitingDeadline
            );
            room.tickets.put(ticketId, ticket);
            room.waiting.put(sequence, ticketId);
            room.deadlines.put(new ExpiryKey(waitingDeadline, ticketId), ticketId);
            room.ownerToTicket.put(memberId, ticketId);
            return new WaitingRoomJoinResult(true, ticketId, sequence);
        } finally {
            room.lock.unlock();
        }
    }

    /** ticket 상태를 snapshot으로 복사한다. */
    @Override
    public Optional<WaitingRoomTicketSnapshot> find(long performanceTimeId, UUID ticketId) {
        requirePositive(performanceTimeId, "performanceTimeId");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        RoomState room = room(performanceTimeId);
        room.lock.lock();
        try {
            TicketState ticket = room.tickets.get(ticketId);
            return ticket == null ? Optional.empty() : Optional.of(ticket.snapshot());
        } finally {
            room.lock.unlock();
        }
    }

    /** WAITING ticket의 zero-based 순번을 계산한다. */
    @Override
    public OptionalLong waitingRank(long performanceTimeId, UUID ticketId) {
        requirePositive(performanceTimeId, "performanceTimeId");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        RoomState room = room(performanceTimeId);
        room.lock.lock();
        try {
            TicketState ticket = room.tickets.get(ticketId);
            if (ticket == null || ticket.status != WaitingRoomTicketStatus.WAITING
                    || !ticketId.equals(room.waiting.get(ticket.sequence))) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(room.waiting.headMap(ticket.sequence, false).size());
        } finally {
            room.lock.unlock();
        }
    }

    /** 만료 대상 정리와 FIFO promotion을 한 회차 lock 안에서 수행한다. */
    @Override
    public WaitingRoomPromotionResult promote(
            long performanceTimeId,
            Instant now,
            Duration entryLease,
            int maxActiveSessions,
            int admitPerInterval,
            Duration promotionInterval,
            Duration storageRetention
    ) {
        requirePositive(performanceTimeId, "performanceTimeId");
        Objects.requireNonNull(now, "now must not be null");
        requirePositiveDuration(entryLease, "entryLease");
        requirePositive(maxActiveSessions, "maxActiveSessions");
        requirePositive(admitPerInterval, "admitPerInterval");
        requirePositiveDuration(promotionInterval, "promotionInterval");
        requirePositiveDuration(storageRetention, "storageRetention");

        RoomState room = room(performanceTimeId);
        room.lock.lock();
        try {
            List<WaitingRoomTicketTransition> expired = expireDue(room, now);
            long windowId = Math.floorDiv(now.toEpochMilli(), promotionInterval.toMillis());
            if (room.admissionWindowId != windowId) {
                room.admissionWindowId = windowId;
                room.admittedInWindow = 0;
            }

            List<WaitingRoomTicketTransition> admitted = new ArrayList<>();
            while (room.active.size() < maxActiveSessions
                    && room.admittedInWindow < admitPerInterval) {
                Map.Entry<Long, UUID> first = room.waiting.firstEntry();
                if (first == null) {
                    break;
                }
                UUID ticketId = first.getValue();
                TicketState ticket = room.tickets.get(ticketId);
                if (ticket == null) {
                    room.waiting.remove(first.getKey(), ticketId);
                    continue;
                }
                if (ticket.status != WaitingRoomTicketStatus.WAITING) {
                    room.waiting.remove(first.getKey(), ticketId);
                    room.deadlines.remove(new ExpiryKey(ticket.waitingDeadline, ticketId), ticketId);
                    continue;
                }
                if (!ticket.waitingDeadline.isAfter(now)) {
                    room.waiting.remove(first.getKey(), ticketId);
                    room.deadlines.remove(new ExpiryKey(ticket.waitingDeadline, ticketId), ticketId);
                    markExpired(room, ticket, now);
                    expired.add(new WaitingRoomTicketTransition(ticketId, WaitingRoomTicketStatus.EXPIRED));
                    continue;
                }

                room.waiting.remove(first.getKey(), ticketId);
                room.deadlines.remove(new ExpiryKey(ticket.waitingDeadline, ticketId), ticketId);
                ticket.status = WaitingRoomTicketStatus.ADMITTED;
                ticket.entryExpiresAt = now.plus(entryLease);
                room.active.put(new ExpiryKey(ticket.entryExpiresAt, ticketId), ticketId);
                room.admittedInWindow++;
                admitted.add(new WaitingRoomTicketTransition(ticketId, WaitingRoomTicketStatus.ADMITTED));
            }
            return new WaitingRoomPromotionResult(admitted, expired);
        } finally {
            room.lock.unlock();
        }
    }

    /** owner 검증 후 WAITING 또는 ADMITTED ticket을 취소한다. */
    @Override
    public Optional<WaitingRoomTicketSnapshot> cancel(
            long performanceTimeId,
            long memberId,
            UUID ticketId,
            Instant now,
            Duration storageRetention
    ) {
        return transition(performanceTimeId, memberId, ticketId, WaitingRoomTicketStatus.CANCELED, now, storageRetention);
    }

    /** owner 검증 후 ADMITTED ticket을 완료한다. */
    @Override
    public Optional<WaitingRoomTicketSnapshot> complete(
            long performanceTimeId,
            long memberId,
            UUID ticketId,
            Instant now,
            Duration storageRetention
    ) {
        return transition(performanceTimeId, memberId, ticketId, WaitingRoomTicketStatus.COMPLETED, now, storageRetention);
    }

    /** waiting·active index의 due ticket을 EXPIRED로 전이한다. */
    private List<WaitingRoomTicketTransition> expireDue(RoomState room, Instant now) {
        List<WaitingRoomTicketTransition> expired = new ArrayList<>();
        while (true) {
            Map.Entry<ExpiryKey, UUID> first = room.deadlines.firstEntry();
            if (first == null || first.getKey().expiresAt.isAfter(now)) {
                break;
            }
            room.deadlines.pollFirstEntry();
            TicketState ticket = room.tickets.get(first.getValue());
            if (ticket == null || ticket.status != WaitingRoomTicketStatus.WAITING
                    || !ticket.waitingDeadline.equals(first.getKey().expiresAt)) {
                continue;
            }
            room.waiting.remove(ticket.sequence, ticket.ticketId);
            markExpired(room, ticket, now);
            expired.add(new WaitingRoomTicketTransition(ticket.ticketId, WaitingRoomTicketStatus.EXPIRED));
        }
        while (true) {
            Map.Entry<ExpiryKey, UUID> first = room.active.firstEntry();
            if (first == null || first.getKey().expiresAt.isAfter(now)) {
                break;
            }
            room.active.pollFirstEntry();
            TicketState ticket = room.tickets.get(first.getValue());
            if (ticket == null || ticket.status != WaitingRoomTicketStatus.ADMITTED
                    || ticket.entryExpiresAt == null
                    || !ticket.entryExpiresAt.equals(first.getKey().expiresAt)) {
                continue;
            }
            markExpired(room, ticket, now);
            expired.add(new WaitingRoomTicketTransition(ticket.ticketId, WaitingRoomTicketStatus.EXPIRED));
        }
        return expired;
    }

    /** 상태 전이와 관련 index 정리를 회차 lock 안에서 수행한다. */
    private Optional<WaitingRoomTicketSnapshot> transition(
            long performanceTimeId,
            long memberId,
            UUID ticketId,
            WaitingRoomTicketStatus target,
            Instant now,
            Duration storageRetention
    ) {
        requirePositive(performanceTimeId, "performanceTimeId");
        requirePositive(memberId, "memberId");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        requirePositiveDuration(storageRetention, "storageRetention");
        RoomState room = room(performanceTimeId);
        room.lock.lock();
        try {
            TicketState ticket = room.tickets.get(ticketId);
            if (ticket == null || ticket.memberId != memberId) {
                return Optional.empty();
            }
            boolean allowed = target == WaitingRoomTicketStatus.CANCELED
                    ? ticket.status == WaitingRoomTicketStatus.WAITING || ticket.status == WaitingRoomTicketStatus.ADMITTED
                    : ticket.status == WaitingRoomTicketStatus.ADMITTED;
            if (!allowed) {
                return Optional.empty();
            }
            removeActiveIndexes(room, ticket);
            ticket.status = target;
            ticket.entryExpiresAt = null;
            ticket.completedAt = now;
            room.ownerToTicket.remove(memberId, ticketId);
            return Optional.of(ticket.snapshot());
        } finally {
            room.lock.unlock();
        }
    }

    /** 만료 전이에서 ticket과 owner·index를 함께 정리한다. */
    private void markExpired(RoomState room, TicketState ticket, Instant now) {
        removeActiveIndexes(room, ticket);
        ticket.status = WaitingRoomTicketStatus.EXPIRED;
        ticket.entryExpiresAt = null;
        ticket.completedAt = now;
        room.ownerToTicket.remove(ticket.memberId, ticket.ticketId);
    }

    /** 현재 상태에 맞는 waiting·deadline·active index를 제거한다. */
    private void removeActiveIndexes(RoomState room, TicketState ticket) {
        room.waiting.remove(ticket.sequence, ticket.ticketId);
        room.deadlines.remove(new ExpiryKey(ticket.waitingDeadline, ticket.ticketId), ticket.ticketId);
        if (ticket.entryExpiresAt != null) {
            room.active.remove(new ExpiryKey(ticket.entryExpiresAt, ticket.ticketId), ticket.ticketId);
        }
    }

    private RoomState room(long performanceTimeId) {
        return rooms.computeIfAbsent(performanceTimeId, ignored -> new RoomState());
    }

    private void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private void requirePositiveDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static final class RoomState {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicLong sequence = new AtomicLong();
        private final ConcurrentSkipListMap<Long, UUID> waiting = new ConcurrentSkipListMap<>();
        private final ConcurrentSkipListMap<ExpiryKey, UUID> deadlines = new ConcurrentSkipListMap<>();
        private final ConcurrentSkipListMap<ExpiryKey, UUID> active = new ConcurrentSkipListMap<>();
        private final ConcurrentHashMap<UUID, TicketState> tickets = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, UUID> ownerToTicket = new ConcurrentHashMap<>();
        private long admissionWindowId = Long.MIN_VALUE;
        private int admittedInWindow;
    }

    private static final class TicketState {
        private final UUID ticketId;
        private final long memberId;
        private final long performanceTimeId;
        private final long sequence;
        private final Instant enqueuedAt;
        private final Instant waitingDeadline;
        private WaitingRoomTicketStatus status = WaitingRoomTicketStatus.WAITING;
        private Instant entryExpiresAt;
        private Instant completedAt;

        private TicketState(
                UUID ticketId,
                long memberId,
                long performanceTimeId,
                long sequence,
                Instant enqueuedAt,
                Instant waitingDeadline
        ) {
            this.ticketId = ticketId;
            this.memberId = memberId;
            this.performanceTimeId = performanceTimeId;
            this.sequence = sequence;
            this.enqueuedAt = enqueuedAt;
            this.waitingDeadline = waitingDeadline;
        }

        private WaitingRoomTicketSnapshot snapshot() {
            return new WaitingRoomTicketSnapshot(
                    ticketId,
                    memberId,
                    performanceTimeId,
                    status,
                    sequence,
                    enqueuedAt,
                    waitingDeadline,
                    entryExpiresAt
            );
        }
    }

    private record ExpiryKey(Instant expiresAt, UUID ticketId) implements Comparable<ExpiryKey> {
        @Override
        public int compareTo(ExpiryKey other) {
            int timeComparison = expiresAt.compareTo(other.expiresAt);
            return timeComparison != 0 ? timeComparison : ticketId.compareTo(other.ticketId);
        }
    }
}

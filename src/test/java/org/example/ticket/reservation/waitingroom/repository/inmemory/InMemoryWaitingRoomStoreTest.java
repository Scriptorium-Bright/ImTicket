package org.example.ticket.reservation.waitingroom.repository.inmemory;

import org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinResult;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomTicketSnapshot;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomTicketTransition;
import org.example.ticket.reservation.waitingroom.exception.WaitingRoomCapacityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Redis Waiting Room 저장소 테스트와 같은 계약을 메모리 대안에 적용한다. */
class InMemoryWaitingRoomStoreTest {

    private static final long PERFORMANCE_TIME_ID = 7001L;
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private InMemoryWaitingRoomStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryWaitingRoomStore();
    }

    /** 동일 owner join dedupe, sequence 순서, active 상한을 함께 검증한다. */
    @Test
    void joinsPromotesAndCompletesWithinActiveCapacity() {
        UUID first = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID second = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Duration retention = Duration.ofHours(1);

        WaitingRoomJoinResult firstJoin = store.join(
                PERFORMANCE_TIME_ID, 11L, first, NOW, NOW.plus(Duration.ofMinutes(30)), retention, 10
        );
        WaitingRoomJoinResult duplicateJoin = store.join(
                PERFORMANCE_TIME_ID, 11L, second, NOW, NOW.plus(Duration.ofMinutes(30)), retention, 10
        );
        store.join(PERFORMANCE_TIME_ID, 12L, second, NOW, NOW.plus(Duration.ofMinutes(30)), retention, 10);

        assertThat(firstJoin.created()).isTrue();
        assertThat(firstJoin.sequence()).isEqualTo(1L);
        assertThat(duplicateJoin.created()).isFalse();
        assertThat(duplicateJoin.ticketId()).isEqualTo(first);
        assertThat(store.waitingRank(PERFORMANCE_TIME_ID, first)).hasValue(0L);
        assertThat(store.waitingRank(PERFORMANCE_TIME_ID, second)).hasValue(1L);

        assertThat(store.promote(
                PERFORMANCE_TIME_ID, NOW, Duration.ofMinutes(5), 1, 2, Duration.ofSeconds(1), retention
        ).admitted()).extracting(WaitingRoomTicketTransition::ticketId).containsExactly(first);
        assertThat(store.promote(
                PERFORMANCE_TIME_ID, NOW, Duration.ofMinutes(5), 1, 2, Duration.ofSeconds(1), retention
        ).admitted()).isEmpty();

        assertThat(store.find(PERFORMANCE_TIME_ID, first)).get()
                .extracting(WaitingRoomTicketSnapshot::status)
                .isEqualTo(WaitingRoomTicketStatus.ADMITTED);
        assertThat(store.complete(PERFORMANCE_TIME_ID, 11L, first, NOW, retention)).get()
                .extracting(WaitingRoomTicketSnapshot::status)
                .isEqualTo(WaitingRoomTicketStatus.COMPLETED);
        assertThat(store.promote(
                PERFORMANCE_TIME_ID, NOW, Duration.ofMinutes(5), 1, 2, Duration.ofSeconds(1), retention
        ).admitted()).extracting(WaitingRoomTicketTransition::ticketId).containsExactly(second);
    }

    /** 한 번의 batch가 FIFO 순서와 interval quota를 함께 지키는지 검증한다. */
    @Test
    void promotesCandidatesInFifoOrderWithinBatchQuota() {
        Duration retention = Duration.ofHours(1);
        List<UUID> tickets = List.of(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"),
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"),
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3"),
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4"),
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa5")
        );
        for (int index = 0; index < tickets.size(); index++) {
            store.join(
                    PERFORMANCE_TIME_ID,
                    100L + index,
                    tickets.get(index),
                    NOW,
                    NOW.plus(Duration.ofMinutes(30)),
                    retention,
                    10
            );
        }

        assertThat(store.promote(
                PERFORMANCE_TIME_ID, NOW, Duration.ofMinutes(5), 10, 3, Duration.ofSeconds(1), retention
        ).admitted()).extracting(WaitingRoomTicketTransition::ticketId)
                .containsExactlyElementsOf(tickets.subList(0, 3));
        assertThat(store.promote(
                PERFORMANCE_TIME_ID, NOW, Duration.ofMinutes(5), 10, 3, Duration.ofSeconds(1), retention
        ).admitted()).isEmpty();
        assertThat(store.promote(
                PERFORMANCE_TIME_ID, NOW.plusSeconds(1), Duration.ofMinutes(5), 10, 3, Duration.ofSeconds(1), retention
        ).admitted()).extracting(WaitingRoomTicketTransition::ticketId)
                .containsExactlyElementsOf(tickets.subList(3, 5));
    }

    /** waiting deadline이 due scan에서 EXPIRED로 정리되는지 검증한다. */
    @Test
    void expiresWaitingTicketWhenDeadlineIsDue() {
        Duration retention = Duration.ofHours(1);
        UUID waiting = UUID.fromString("33333333-3333-3333-3333-333333333333");
        store.join(PERFORMANCE_TIME_ID, 13L, waiting, NOW, NOW.plusSeconds(1), retention, 10);
        store.promote(
                PERFORMANCE_TIME_ID, NOW.plusSeconds(2), Duration.ofMinutes(1), 10, 10, Duration.ofSeconds(1), retention
        );

        assertThat(store.find(PERFORMANCE_TIME_ID, waiting)).get()
                .extracting(WaitingRoomTicketSnapshot::status)
                .isEqualTo(WaitingRoomTicketStatus.EXPIRED);
        assertThat(store.waitingRank(PERFORMANCE_TIME_ID, waiting)).isEmpty();
    }

    /** 만료 scan 뒤에도 due 후보가 승급되지 않고 EXPIRED로 정리되는지 검증한다. */
    @Test
    void doesNotPromoteExpiredCandidateAfterExpiryBatchIsExhausted() {
        UUID first = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID second = UUID.fromString("55555555-5555-5555-5555-555555555555");
        Duration retention = Duration.ofHours(1);
        Instant deadline = NOW.plusSeconds(1);

        store.join(PERFORMANCE_TIME_ID, 21L, first, NOW, deadline, retention, 10);
        store.join(PERFORMANCE_TIME_ID, 22L, second, NOW, deadline, retention, 10);
        store.promote(
                PERFORMANCE_TIME_ID, NOW.plusSeconds(2), Duration.ofMinutes(5), 10, 1, Duration.ofSeconds(1), retention
        );

        assertThat(store.find(PERFORMANCE_TIME_ID, first)).get()
                .extracting(WaitingRoomTicketSnapshot::status)
                .isEqualTo(WaitingRoomTicketStatus.EXPIRED);
        assertThat(store.find(PERFORMANCE_TIME_ID, second)).get()
                .extracting(WaitingRoomTicketSnapshot::status)
                .isEqualTo(WaitingRoomTicketStatus.EXPIRED);
    }

    /** admitted lease가 due scan에서 EXPIRED로 정리되는지 검증한다. */
    @Test
    void expiresAdmittedTicketWhenLeaseIsDue() {
        UUID ticketId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        Duration retention = Duration.ofHours(1);
        store.join(PERFORMANCE_TIME_ID, 23L, ticketId, NOW, NOW.plus(Duration.ofMinutes(30)), retention, 10);
        store.promote(
                PERFORMANCE_TIME_ID, NOW, Duration.ofMinutes(1), 10, 10, Duration.ofSeconds(1), retention
        );
        store.promote(
                PERFORMANCE_TIME_ID, NOW.plus(Duration.ofMinutes(2)), Duration.ofMinutes(1), 10, 10, Duration.ofSeconds(1), retention
        );

        assertThat(store.find(PERFORMANCE_TIME_ID, ticketId)).get()
                .extracting(WaitingRoomTicketSnapshot::status)
                .isEqualTo(WaitingRoomTicketStatus.EXPIRED);
    }

    /** queue capacity 초과가 새 ticket을 만들지 않고 명시적 예외를 반환하는지 검증한다. */
    @Test
    void rejectsJoinWhenWaitingCapacityIsFull() {
        Duration retention = Duration.ofHours(1);
        UUID first = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID second = UUID.fromString("66666666-6666-6666-6666-666666666666");

        store.join(PERFORMANCE_TIME_ID, 41L, first, NOW, NOW.plus(Duration.ofMinutes(30)), retention, 1);

        assertThatThrownBy(() -> store.join(
                PERFORMANCE_TIME_ID, 42L, second, NOW, NOW.plus(Duration.ofMinutes(30)), retention, 1
        )).isInstanceOf(WaitingRoomCapacityException.class);
        assertThat(store.find(PERFORMANCE_TIME_ID, second)).isEmpty();
    }

    /** 같은 owner의 동시 join이 하나의 ticket mapping으로 수렴하는지 검증한다. */
    @Test
    void convergesConcurrentJoinRequestsForSameOwner() throws Exception {
        int requestCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WaitingRoomJoinResult>> futures = new ArrayList<>();
        Duration retention = Duration.ofHours(1);

        try {
            for (int index = 0; index < requestCount; index++) {
                UUID ticketId = UUID.nameUUIDFromBytes(("ticket-" + index).getBytes());
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return store.join(
                            PERFORMANCE_TIME_ID, 51L, ticketId, NOW,
                            NOW.plus(Duration.ofMinutes(30)), retention, 10
                    );
                }));
            }
            ready.await();
            start.countDown();

            List<WaitingRoomJoinResult> results = new ArrayList<>();
            for (Future<WaitingRoomJoinResult> future : futures) {
                results.add(future.get());
            }

            UUID convergedTicketId = results.get(0).ticketId();
            assertThat(results).extracting(WaitingRoomJoinResult::ticketId).containsOnly(convergedTicketId);
            assertThat(store.waitingRank(PERFORMANCE_TIME_ID, convergedTicketId)).hasValue(0L);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 같은 JVM의 동시 promotion이 active 상한을 넘기지 않는지 검증한다. */
    @Test
    void concurrentPromotionsRespectActiveCapacity() throws Exception {
        Duration retention = Duration.ofHours(1);
        for (int index = 0; index < 100; index++) {
            UUID ticketId = UUID.nameUUIDFromBytes(("promotion-" + index).getBytes());
            store.join(PERFORMANCE_TIME_ID, 1000L + index, ticketId, NOW,
                    NOW.plus(Duration.ofMinutes(30)), retention, 200);
        }

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return store.promote(
                            PERFORMANCE_TIME_ID, NOW, Duration.ofMinutes(5), 10, 100,
                            Duration.ofSeconds(1), retention
                    ).admitted().size();
                }));
            }
            start.countDown();
            int promoted = 0;
            for (Future<Integer> future : futures) {
                promoted += future.get();
            }
            assertThat(promoted).isEqualTo(10);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 서로 다른 메모리 저장소가 admission quota를 공유하지 않는 현재 한계를 확인한다. */
    @Test
    void separateMemoryStoresDoNotShareAdmissionQuota() {
        Duration retention = Duration.ofHours(1);
        InMemoryWaitingRoomStore secondStore = new InMemoryWaitingRoomStore();
        UUID first = UUID.fromString("77777777-7777-7777-7777-777777777777");
        UUID second = UUID.fromString("88888888-8888-8888-8888-888888888888");
        store.join(PERFORMANCE_TIME_ID, 61L, first, NOW, NOW.plus(Duration.ofMinutes(30)), retention, 10);
        secondStore.join(PERFORMANCE_TIME_ID, 62L, second, NOW, NOW.plus(Duration.ofMinutes(30)), retention, 10);

        assertThat(store.promote(
                PERFORMANCE_TIME_ID, NOW, Duration.ofMinutes(5), 10, 1, Duration.ofSeconds(1), retention
        ).admitted()).hasSize(1);
        assertThat(secondStore.promote(
                PERFORMANCE_TIME_ID, NOW, Duration.ofMinutes(5), 10, 1, Duration.ofSeconds(1), retention
        ).admitted()).hasSize(1);
    }
}

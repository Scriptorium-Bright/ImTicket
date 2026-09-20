package org.example.ticket.lifecycle.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ticket.lifecycle.event.LifecycleActorType;
import org.example.ticket.lifecycle.event.LifecycleEntityType;
import org.example.ticket.lifecycle.event.LifecycleEvent;
import org.example.ticket.lifecycle.event.LifecycleEventDecisionReader;
import org.example.ticket.lifecycle.event.LifecycleEventPayload;
import org.example.ticket.lifecycle.event.LifecycleEventRepository;
import org.example.ticket.lifecycle.event.LifecycleEventType;
import org.example.ticket.lifecycle.event.LifecycleStateChange;
import org.example.ticket.payment.constant.PaymentAttemptStatus;
import org.example.ticket.payment.constant.PaymentOrderStatus;
import org.example.ticket.util.constant.ReservationStatus;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 30,000건의 사건을 별도 조회 모델로 Replay하는 Phase 4 측정 시험이다. */
@Tag("phase4-measurement")
@DataJpaTest
@Import({LifecycleEventDecisionReader.class, LifecycleReconstructionService.class, LifecycleReplayService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LifecycleReplayPhase4MeasurementTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int LIFECYCLE_COUNT = 7_500;
    private static final int EVENTS_PER_LIFECYCLE = 4;
    private static final int TOTAL_EVENT_COUNT = LIFECYCLE_COUNT * EVENTS_PER_LIFECYCLE;
    private static final int TARGET_PROJECTION_VERSION = 2;
    private static final long LIFECYCLE_ID_BASE = 10_000_000L;
    private static final long SEAT_ID_BASE = 20_000_000L;
    private static final long PAYMENT_ORDER_ID_BASE = 30_000_000L;
    private static final long PAYMENT_ATTEMPT_ID_BASE = 40_000_000L;

    @Autowired
    private LifecycleEventRepository eventRepository;

    @Autowired
    private LifecycleReplayService replayService;

    @Autowired
    private LifecycleSnapshotRepository snapshotRepository;

    @Autowired
    private LifecycleEventApplicationRepository applicationRepository;

    @Autowired
    private LifecycleReplayRunRepository replayRunRepository;

    @Test
    void replaysThirtyThousandEventsAndReportsLatency() {
        List<Long> lifecycleIds = appendNormalEvents();
        List<Long> replayDurationsNanos = new ArrayList<>(lifecycleIds.size());
        int completed = 0;
        int failed = 0;

        long startedAt = System.nanoTime();
        for (Long lifecycleId : lifecycleIds) {
            long replayStartedAt = System.nanoTime();
            LifecycleReplayResult result = replayService.replayLifecycle(
                    lifecycleId,
                    TARGET_PROJECTION_VERSION
            );
            replayDurationsNanos.add(System.nanoTime() - replayStartedAt);
            if (result.status() == LifecycleReplayRunStatus.COMPLETED) {
                completed++;
            } else {
                failed++;
            }
        }
        long elapsedNanos = System.nanoTime() - startedAt;

        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double throughput = TOTAL_EVENT_COUNT / elapsedSeconds;
        double p50Millis = percentileMillis(replayDurationsNanos, 0.50);
        double p95Millis = percentileMillis(replayDurationsNanos, 0.95);
        double p99Millis = percentileMillis(replayDurationsNanos, 0.99);

        System.out.printf(
                "phase4.replay.measurement events=%d lifecycles=%d completed=%d failed=%d "
                        + "elapsedSeconds=%.3f throughputEventsPerSecond=%.1f "
                        + "replayP50Ms=%.3f replayP95Ms=%.3f replayP99Ms=%.3f maxBacklogEvents=%d%n",
                TOTAL_EVENT_COUNT,
                LIFECYCLE_COUNT,
                completed,
                failed,
                elapsedSeconds,
                throughput,
                p50Millis,
                p95Millis,
                p99Millis,
                TOTAL_EVENT_COUNT
        );

        assertThat(completed).isEqualTo(LIFECYCLE_COUNT);
        assertThat(failed).isZero();
        assertThat(snapshotRepository.count()).isEqualTo(LIFECYCLE_COUNT);
        assertThat(applicationRepository.count()).isEqualTo(TOTAL_EVENT_COUNT);
        assertThat(replayRunRepository.count()).isEqualTo(LIFECYCLE_COUNT);
    }

    private List<Long> appendNormalEvents() {
        List<LifecycleEvent> events = new ArrayList<>(TOTAL_EVENT_COUNT);
        List<Long> lifecycleIds = new ArrayList<>(LIFECYCLE_COUNT);
        LocalDateTime occurredAt = LocalDateTime.now();
        for (int index = 0; index < LIFECYCLE_COUNT; index++) {
            long lifecycleId = LIFECYCLE_ID_BASE + index;
            long seatId = SEAT_ID_BASE + index;
            long paymentOrderId = PAYMENT_ORDER_ID_BASE + index;
            long paymentAttemptId = PAYMENT_ATTEMPT_ID_BASE + index;
            lifecycleIds.add(lifecycleId);
            events.add(event(
                    lifecycleId,
                    1L,
                    0,
                    LifecycleEventType.RESERVATION_CREATED,
                    null,
                    null,
                    LifecycleActorType.RESERVATION_SERVICE,
                    occurredAt,
                    payload(List.of(seatId), List.of(
                            change(LifecycleEntityType.RESERVATION, lifecycleId,
                                    null, ReservationStatus.PENDING_PAYMENT.name()),
                            change(LifecycleEntityType.SEAT, seatId,
                                    SeatStatus.AVAILABLE.name(), SeatStatus.LOCKED.name())
                    ), "RESERVATION_CREATED")
            ));
            events.add(event(
                    lifecycleId,
                    2L,
                    0,
                    LifecycleEventType.PAYMENT_PREPARED,
                    paymentOrderId,
                    paymentAttemptId,
                    LifecycleActorType.PAYMENT_PREPARATION,
                    occurredAt,
                    payload(List.of(), List.of(
                            change(LifecycleEntityType.PAYMENT_ORDER, paymentOrderId,
                                    null, PaymentOrderStatus.READY.name()),
                            change(LifecycleEntityType.PAYMENT_ATTEMPT, paymentAttemptId,
                                    null, PaymentAttemptStatus.READY.name())
                    ), "PAYMENT_PREPARED")
            ));
            events.add(event(
                    lifecycleId,
                    3L,
                    0,
                    LifecycleEventType.PAYMENT_APPROVED,
                    paymentOrderId,
                    paymentAttemptId,
                    LifecycleActorType.PAYMENT_VERIFICATION,
                    occurredAt,
                    payload(List.of(), List.of(
                            change(LifecycleEntityType.PAYMENT_ATTEMPT, paymentAttemptId,
                                    PaymentAttemptStatus.READY.name(), PaymentAttemptStatus.PAID.name())
                    ), "PAYMENT_APPROVED")
            ));
            events.add(event(
                    lifecycleId,
                    3L,
                    1,
                    LifecycleEventType.RESERVATION_COMPLETED,
                    paymentOrderId,
                    paymentAttemptId,
                    LifecycleActorType.PAYMENT_VERIFICATION,
                    occurredAt,
                    payload(List.of(seatId), List.of(
                            change(LifecycleEntityType.RESERVATION, lifecycleId,
                                    ReservationStatus.PENDING_PAYMENT.name(), ReservationStatus.SUCCESS.name()),
                            change(LifecycleEntityType.SEAT, seatId,
                                    SeatStatus.LOCKED.name(), SeatStatus.RESERVED.name()),
                            change(LifecycleEntityType.PAYMENT_ORDER, paymentOrderId,
                                    PaymentOrderStatus.READY.name(), PaymentOrderStatus.APPLIED.name())
                    ), "PAYMENT_APPLIED_TO_RESERVATION")
            ));
        }
        eventRepository.saveAllAndFlush(events);
        return lifecycleIds;
    }

    private LifecycleEvent event(
            long lifecycleId,
            long decisionVersion,
            int eventOrdinal,
            LifecycleEventType type,
            Long paymentOrderId,
            Long paymentAttemptId,
            LifecycleActorType actorType,
            LocalDateTime occurredAt,
            LifecycleEventPayload payload
    ) {
        return LifecycleEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(type.wireValue())
                .schemaVersion(1)
                .lifecycleId(lifecycleId)
                .paymentOrderId(paymentOrderId)
                .paymentAttemptId(paymentAttemptId)
                .decisionVersion(decisionVersion)
                .eventOrdinal(eventOrdinal)
                .commitGroupId("measurement-group-" + lifecycleId + "-" + decisionVersion)
                .actorType(actorType.name())
                .occurredAt(occurredAt)
                .payload(json(payload))
                .build();
    }

    private LifecycleStateChange change(
            LifecycleEntityType entityType,
            long entityId,
            String fromState,
            String toState
    ) {
        return new LifecycleStateChange(entityType, String.valueOf(entityId), fromState, toState);
    }

    private LifecycleEventPayload payload(
            List<Long> seatIds,
            List<LifecycleStateChange> changes,
            String reasonCode
    ) {
        return new LifecycleEventPayload(seatIds, changes, reasonCode);
    }

    private String json(LifecycleEventPayload payload) {
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private double percentileMillis(List<Long> durationsNanos, double percentile) {
        List<Long> sorted = durationsNanos.stream().sorted(Comparator.naturalOrder()).toList();
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index) / 1_000_000.0;
    }
}

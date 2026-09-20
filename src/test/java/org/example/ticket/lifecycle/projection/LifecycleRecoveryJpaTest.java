package org.example.ticket.lifecycle.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ticket.lifecycle.event.LifecycleActorType;
import org.example.ticket.lifecycle.event.LifecycleEntityType;
import org.example.ticket.lifecycle.event.LifecycleEvent;
import org.example.ticket.lifecycle.event.LifecycleEventDecisionReader;
import org.example.ticket.lifecycle.event.LifecycleEventRepository;
import org.example.ticket.lifecycle.event.LifecycleEventType;
import org.example.ticket.lifecycle.event.LifecycleEventPayload;
import org.example.ticket.lifecycle.event.LifecycleStateChange;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.payment.constant.PaymentAttemptStatus;
import org.example.ticket.payment.constant.PaymentOrderStatus;
import org.example.ticket.payment.model.PaymentAttempt;
import org.example.ticket.payment.model.PaymentOrder;
import org.example.ticket.payment.repository.PaymentAttemptRepository;
import org.example.ticket.payment.repository.PaymentOrderRepository;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.performance.repository.PerformanceRepository;
import org.example.ticket.performance.repository.PerformanceTimeRepository;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.domain.ReservedSeat;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.reservation.booking.repository.ReservationRepository;
import org.example.ticket.reservation.booking.repository.SeatRepository;
import org.example.ticket.util.constant.ReservationStatus;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({
        LifecycleEventDecisionReader.class,
        LifecycleReconstructionService.class,
        LifecycleReconciliationService.class,
        LifecycleReplayService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LifecycleRecoveryJpaTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PerformanceRepository performanceRepository;

    @Autowired
    private PerformanceTimeRepository performanceTimeRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private LifecycleEventRepository eventRepository;

    @Autowired
    private LifecycleReconstructionService reconstructionService;

    @Autowired
    private LifecycleReconciliationService reconciliationService;

    @Autowired
    private LifecycleReplayService replayService;

    @Autowired
    private LifecycleSnapshotRepository snapshotRepository;

    @Autowired
    private LifecycleEventApplicationRepository applicationRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void reconcilesConsistentStateAndReportsLaterSourceMutation() {
        Fixture fixture = fixture();
        appendAllEvents(fixture);
        reconstructAll(fixture.reservationId(), 3L);

        LifecycleReconciliationResult consistent = reconciliationService.reconcile(1, fixture.reservationId());
        assertThat(consistent.trustStatus()).isEqualTo(LifecycleTrustStatus.CONSISTENT);
        assertThat(consistent.differences()).isEmpty();
        assertThat(snapshotRepository.findById(new LifecycleProjectionKey(1, fixture.reservationId()))
                .orElseThrow().getReconciledSourceVersion()).isEqualTo(3L);

        Seat seat = seatRepository.findById(fixture.seatId()).orElseThrow();
        seat.markAsReserved(SeatStatus.AVAILABLE);
        seatRepository.saveAndFlush(seat);

        LifecycleReconciliationResult mismatch = reconciliationService.reconcile(1, fixture.reservationId());
        assertThat(mismatch.trustStatus()).isEqualTo(LifecycleTrustStatus.MISMATCH);
        assertThat(mismatch.differences()).containsKey("seatStatuses");
    }

    @Test
    void marksMissingSourceDecisionAsIncomplete() {
        Fixture fixture = fixture();
        appendInitialEvents(fixture);
        reconstructionService.reconstruct(new org.example.ticket.lifecycle.event.LifecycleEventDecisionKey(
                fixture.reservationId(), 1L
        ));

        LifecycleReconciliationResult result = reconciliationService.reconcile(1, fixture.reservationId());

        assertThat(result.trustStatus()).isEqualTo(LifecycleTrustStatus.INCOMPLETE);
        assertThat(result.differences().get("missingDecisionVersions")).asList().containsExactly(3L);
    }

    @Test
    void replaysEventsIntoAnIsolatedProjectionVersion() {
        Fixture fixture = fixture();
        appendAllEvents(fixture);
        reconstructAll(fixture.reservationId(), 3L);

        LifecycleReplayResult replay = replayService.replayLifecycle(fixture.reservationId(), 2);

        assertThat(replay.status()).isEqualTo(LifecycleReplayRunStatus.COMPLETED);
        assertThat(replay.processedEvents()).isEqualTo(4);
        assertThat(replay.failedEvents()).isZero();
        LifecycleSnapshot original = snapshotRepository.findById(
                new LifecycleProjectionKey(1, fixture.reservationId())
        ).orElseThrow();
        LifecycleSnapshot replayed = snapshotRepository.findById(
                new LifecycleProjectionKey(2, fixture.reservationId())
        ).orElseThrow();
        assertThat(replayed.getLastAppliedVersion()).isEqualTo(original.getLastAppliedVersion());
        assertThat(replayed.getReservationStatus()).isEqualTo(original.getReservationStatus());
        assertThat(replayed.getPaymentOrderStatus()).isEqualTo(original.getPaymentOrderStatus());
        assertThat(applicationRepository.findAll().stream()
                .filter(application -> application.getId().getProjectionVersion() == 2)
                .count()).isEqualTo(4);
    }

    @Test
    void rollsBackProjectionWhenConsumerTransactionIsInterrupted() {
        Fixture fixture = fixture();
        appendAllEvents(fixture);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        transactionTemplate.execute(status -> {
            reconstructionService.reconstruct(new org.example.ticket.lifecycle.event.LifecycleEventDecisionKey(
                    fixture.reservationId(), 1L
            ));
            status.setRollbackOnly();
            return null;
        });

        assertThat(snapshotRepository.findById(new LifecycleProjectionKey(1, fixture.reservationId())))
                .isEmpty();
        LifecycleReconstructionResult recovered = reconstructionService.reconstruct(
                new org.example.ticket.lifecycle.event.LifecycleEventDecisionKey(fixture.reservationId(), 1L)
        );
        assertThat(recovered.applicationStatus()).isEqualTo(LifecycleApplicationStatus.APPLIED);
        assertThat(snapshotRepository.findById(new LifecycleProjectionKey(1, fixture.reservationId())))
                .isPresent();
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString();
        Member member = memberRepository.save(Member.builder()
                .walletAddress("0x" + suffix.replace("-", ""))
                .nickname("member-" + suffix)
                .role("ROLE_USER")
                .build());
        Performance performance = performanceRepository.save(Performance.builder()
                .title("lifecycle-" + suffix)
                .build());
        PerformanceTime performanceTime = performanceTimeRepository.save(PerformanceTime.builder()
                .performance(performance)
                .showDate(LocalDate.now().plusDays(1))
                .showTime(LocalTime.NOON)
                .build());
        Seat seat = seatRepository.saveAndFlush(Seat.builder()
                .seatFloor(1)
                .seatSection("A")
                .seatRow(1)
                .seatNumber(1)
                .seatType(SeatInfo.VIP)
                .price(45_000)
                .seatStatus(SeatStatus.LOCKED)
                .performanceTime(performanceTime)
                .build());
        Reservation reservation = Reservation.builder()
                .reservationCode("reservation-" + suffix)
                .member(member)
                .totalPrice(45_000)
                .reservationStatus(ReservationStatus.SUCCESS)
                .expiredTime(LocalDateTime.now().minusMinutes(1))
                .build();
        reservation.startLifecycleTracking();
        reservation.advanceLifecycleVersion();
        reservation.advanceLifecycleVersion();
        reservation.setReservedSeats(List.of(ReservedSeat.builder()
                .reservation(reservation)
                .seat(seat)
                .build()));
        reservationRepository.saveAndFlush(reservation);
        PaymentOrder order = paymentOrderRepository.saveAndFlush(PaymentOrder.builder()
                .reservation(reservation)
                .member(member)
                .merchantOrderId("merchant-" + suffix)
                .amount(45_000)
                .currency("KRW")
                .status(PaymentOrderStatus.APPLIED)
                .idempotencyKey("payment-" + suffix)
                .requestHash(suffix.replace("-", ""))
                .build());
        PaymentAttempt attempt = paymentAttemptRepository.saveAndFlush(PaymentAttempt.builder()
                .paymentOrder(order)
                .attemptId("attempt-" + suffix)
                .provider("FAKE")
                .status(PaymentAttemptStatus.PAID)
                .build());
        attempt.markPaid("provider-" + suffix, 45_000, "KRW", LocalDateTime.now());
        paymentAttemptRepository.saveAndFlush(attempt);
        seat.markAsReserved(SeatStatus.RESERVED);
        seatRepository.saveAndFlush(seat);
        return new Fixture(reservation.getId(), order.getId(), seat.getId(), attempt.getId());
    }

    private void appendAllEvents(Fixture fixture) {
        appendInitialEvents(fixture);
        appendEvent(fixture.reservationId(), 3L, 0, LifecycleEventType.PAYMENT_APPROVED,
                fixture.orderId(), fixture.attemptId(), payload(List.of(), List.of(
                        change(LifecycleEntityType.PAYMENT_ATTEMPT, fixture.attemptId(),
                                PaymentAttemptStatus.READY.name(), PaymentAttemptStatus.PAID.name())
                ), "PAYMENT_APPROVED"));
        appendEvent(fixture.reservationId(), 3L, 1, LifecycleEventType.RESERVATION_COMPLETED,
                fixture.orderId(), fixture.attemptId(), payload(List.of(fixture.seatId()), List.of(
                        change(LifecycleEntityType.RESERVATION, fixture.reservationId(),
                                ReservationStatus.PENDING_PAYMENT.name(), ReservationStatus.SUCCESS.name()),
                        change(LifecycleEntityType.SEAT, fixture.seatId(),
                                SeatStatus.LOCKED.name(), SeatStatus.RESERVED.name()),
                        change(LifecycleEntityType.PAYMENT_ORDER, fixture.orderId(),
                                PaymentOrderStatus.READY.name(), PaymentOrderStatus.APPLIED.name())
                ), "PAYMENT_APPLIED_TO_RESERVATION"));
    }

    private void appendInitialEvents(Fixture fixture) {
        appendEvent(fixture.reservationId(), 1L, 0, LifecycleEventType.RESERVATION_CREATED,
                null, null, payload(List.of(fixture.seatId()), List.of(
                        change(LifecycleEntityType.RESERVATION, fixture.reservationId(),
                                null, ReservationStatus.PENDING_PAYMENT.name()),
                        change(LifecycleEntityType.SEAT, fixture.seatId(),
                                SeatStatus.AVAILABLE.name(), SeatStatus.LOCKED.name())
                ), "RESERVATION_CREATED"));
        appendEvent(fixture.reservationId(), 2L, 0, LifecycleEventType.PAYMENT_PREPARED,
                fixture.orderId(), fixture.attemptId(), payload(List.of(), List.of(
                        change(LifecycleEntityType.PAYMENT_ORDER, fixture.orderId(),
                                null, PaymentOrderStatus.READY.name()),
                        change(LifecycleEntityType.PAYMENT_ATTEMPT, fixture.attemptId(),
                                null, PaymentAttemptStatus.READY.name())
                ), "PAYMENT_PREPARED"));
    }

    private void reconstructAll(long lifecycleId, long lastVersion) {
        for (long version = 1; version <= lastVersion; version++) {
            reconstructionService.reconstruct(new org.example.ticket.lifecycle.event.LifecycleEventDecisionKey(
                    lifecycleId, version
            ));
        }
    }

    private void appendEvent(
            long lifecycleId,
            long decisionVersion,
            int ordinal,
            LifecycleEventType type,
            Long orderId,
            Long attemptId,
            LifecycleEventPayload payload
    ) {
        eventRepository.saveAndFlush(LifecycleEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(type.wireValue())
                .schemaVersion(1)
                .lifecycleId(lifecycleId)
                .paymentOrderId(orderId)
                .paymentAttemptId(attemptId)
                .decisionVersion(decisionVersion)
                .eventOrdinal(ordinal)
                .commitGroupId("group-" + lifecycleId + "-" + decisionVersion)
                .actorType(LifecycleActorType.PAYMENT_VERIFICATION.name())
                .occurredAt(LocalDateTime.now())
                .payload(json(payload))
                .build());
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

    private record Fixture(Long reservationId, Long orderId, Long seatId, Long attemptId) {
    }
}

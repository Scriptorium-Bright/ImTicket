package org.example.ticket.lifecycle.event;

import org.example.ticket.lifecycle.projection.LifecyclePathClassification;
import org.example.ticket.lifecycle.projection.LifecycleProjectionKey;
import org.example.ticket.lifecycle.projection.LifecycleReconstructionService;
import org.example.ticket.lifecycle.projection.LifecycleSnapshotRepository;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.payment.constant.PaymentAttemptStatus;
import org.example.ticket.payment.constant.PaymentOrderStatus;
import org.example.ticket.payment.dto.VerifiedPaymentSnapshot;
import org.example.ticket.payment.model.PaymentAttempt;
import org.example.ticket.payment.model.PaymentOrder;
import org.example.ticket.payment.repository.PaymentAttemptRepository;
import org.example.ticket.payment.repository.PaymentOrderRepository;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.performance.repository.PerformanceRepository;
import org.example.ticket.performance.repository.PerformanceTimeRepository;
import org.example.ticket.reservation.booking.cache.SeatMapInvalidationPublisher;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.domain.ReservedSeat;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.reservation.booking.repository.ReservationRepository;
import org.example.ticket.reservation.booking.repository.SeatRepository;
import org.example.ticket.reservation.booking.service.ReservationCompletionService;
import org.example.ticket.reservation.booking.service.ReservationExpirationService;
import org.example.ticket.util.constant.ReservationStatus;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "lifecycle.tracing.event-writer.enabled=true")
@Import({
        LifecycleEventWriter.class,
        LifecycleEventDecisionReader.class,
        LifecycleReconstructionService.class,
        ReservationCompletionService.class,
        ReservationExpirationService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LifecycleEventProductionJpaTest {

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
    private LifecycleEventRepository lifecycleEventRepository;

    @Autowired
    private LifecycleEventWriter lifecycleEventWriter;

    @Autowired
    private LifecycleReconstructionService lifecycleReconstructionService;

    @Autowired
    private LifecycleSnapshotRepository lifecycleSnapshotRepository;

    @Autowired
    private ReservationCompletionService reservationCompletionService;

    @Autowired
    private ReservationExpirationService reservationExpirationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private SeatMapInvalidationPublisher seatMapInvalidationPublisher;

    @Test
    void distinguishesExpirationFirstPathWithSeparateCommittedDecisions() {
        Fixture fixture = inTransaction(ignored -> fixture(LocalDateTime.now().minusMinutes(1)));
        recordInitialEvents(fixture);

        inTransaction(ignored -> {
            reservationExpirationService.expireReservations(LocalDateTime.now(), 100);
            return null;
        });
        inTransaction(ignored -> reservationCompletionService.complete(
                fixture.paymentOrderId(), fixture.walletAddress(), fixture.verifiedPayment()
        ));

        List<LifecycleEvent> events = lifecycleEventRepository
                .findByLifecycleIdOrderByDecisionVersionAscEventOrdinalAsc(fixture.reservationId());
        assertThat(events).extracting(LifecycleEvent::getEventType).containsExactly(
                "ReservationCreated",
                "PaymentPrepared",
                "ReservationExpired",
                "PaymentApproved",
                "PaymentRefundPending"
        );
        LifecycleEvent expired = events.get(2);
        LifecycleEvent approved = events.get(3);
        LifecycleEvent refundPending = events.get(4);
        assertThat(expired.getActorType()).isEqualTo(LifecycleActorType.EXPIRATION_SCHEDULER.name());
        assertThat(expired.getDecisionVersion()).isLessThan(approved.getDecisionVersion());
        assertThat(approved.getCommitGroupId()).isEqualTo(refundPending.getCommitGroupId());
        assertThat(expired.getCommitGroupId()).isNotEqualTo(approved.getCommitGroupId());
        assertTerminalExpiredRefundPendingState(fixture);
        reconstruct(fixture.reservationId(), 4L);
        assertThat(lifecycleSnapshotRepository.findById(new LifecycleProjectionKey(1, fixture.reservationId()))
                .orElseThrow().getPathClassification()).isEqualTo(LifecyclePathClassification.EXPIRATION_FIRST);
    }

    @Test
    void distinguishesPaymentHandlerExpiredPathWithOneCommittedDecision() {
        Fixture fixture = inTransaction(ignored -> fixture(LocalDateTime.now().minusMinutes(1)));
        recordInitialEvents(fixture);

        inTransaction(ignored -> reservationCompletionService.complete(
                fixture.paymentOrderId(), fixture.walletAddress(), fixture.verifiedPayment()
        ));

        List<LifecycleEvent> events = lifecycleEventRepository
                .findByLifecycleIdOrderByDecisionVersionAscEventOrdinalAsc(fixture.reservationId());
        assertThat(events).extracting(LifecycleEvent::getEventType).containsExactly(
                "ReservationCreated",
                "PaymentPrepared",
                "PaymentApproved",
                "ReservationExpired",
                "PaymentRefundPending"
        );
        List<LifecycleEvent> paymentDecision = events.subList(2, 5);
        assertThat(paymentDecision).extracting(LifecycleEvent::getDecisionVersion).containsOnly(3L);
        assertThat(paymentDecision).extracting(LifecycleEvent::getCommitGroupId)
                .containsOnly(paymentDecision.getFirst().getCommitGroupId());
        assertThat(paymentDecision.get(1).getActorType())
                .isEqualTo(LifecycleActorType.PAYMENT_VERIFICATION.name());
        assertTerminalExpiredRefundPendingState(fixture);
        reconstruct(fixture.reservationId(), 3L);
        assertThat(lifecycleSnapshotRepository.findById(new LifecycleProjectionKey(1, fixture.reservationId()))
                .orElseThrow().getPathClassification()).isEqualTo(LifecyclePathClassification.PAYMENT_HANDLER_EXPIRED);
    }

    private Fixture fixture(LocalDateTime expiredAt) {
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
        Seat seat = seatRepository.save(Seat.builder()
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
                .reservationStatus(ReservationStatus.PENDING_PAYMENT)
                .expiredTime(expiredAt)
                .build();
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
                .status(PaymentOrderStatus.READY)
                .idempotencyKey("payment-" + suffix)
                .requestHash(suffix.replace("-", ""))
                .build());
        PaymentAttempt attempt = paymentAttemptRepository.saveAndFlush(PaymentAttempt.builder()
                .paymentOrder(order)
                .attemptId("attempt-" + suffix)
                .provider("FAKE")
                .status(PaymentAttemptStatus.READY)
                .build());
        return new Fixture(
                reservation.getId(),
                order.getId(),
                seat.getId(),
                attempt.getId(),
                member.getWalletAddress(),
                new VerifiedPaymentSnapshot(
                        order.getMerchantOrderId(),
                        "provider-" + suffix, 45_000, "KRW", LocalDateTime.now()
                )
        );
    }

    private void recordInitialEvents(Fixture fixture) {
        inTransaction(ignored -> {
            Reservation reservation = reservationRepository.findById(fixture.reservationId()).orElseThrow();
            lifecycleEventWriter.recordReservationCreated(reservation, new LifecycleEventDraft(
                    LifecycleEventType.RESERVATION_CREATED,
                    LifecycleActorType.RESERVATION_SERVICE,
                    null,
                    null,
                    new LifecycleEventPayload(
                            List.of(fixture.seatId()),
                            List.of(
                                    LifecycleStateChange.created(
                                            LifecycleEntityType.RESERVATION,
                                            reservation.getId(),
                                            ReservationStatus.PENDING_PAYMENT.name()
                                    ),
                                    LifecycleStateChange.changed(
                                            LifecycleEntityType.SEAT,
                                            fixture.seatId(),
                                            SeatStatus.AVAILABLE.name(),
                                            SeatStatus.LOCKED.name()
                                    )
                            ),
                            "RESERVATION_CREATED"
                    )
            ));
            return null;
        });
        inTransaction(ignored -> {
            Reservation reservation = reservationRepository.findById(fixture.reservationId()).orElseThrow();
            lifecycleEventWriter.recordDecision(reservation, List.of(new LifecycleEventDraft(
                    LifecycleEventType.PAYMENT_PREPARED,
                    LifecycleActorType.PAYMENT_PREPARATION,
                    fixture.paymentOrderId(),
                    fixture.paymentAttemptId(),
                    new LifecycleEventPayload(
                            List.of(),
                            List.of(
                                    LifecycleStateChange.created(
                                            LifecycleEntityType.PAYMENT_ORDER,
                                            fixture.paymentOrderId(),
                                            PaymentOrderStatus.READY.name()
                                    ),
                                    LifecycleStateChange.created(
                                            LifecycleEntityType.PAYMENT_ATTEMPT,
                                            fixture.paymentAttemptId(),
                                            PaymentAttemptStatus.READY.name()
                                    )
                            ),
                            "PAYMENT_PREPARED"
                    )
            )));
            return null;
        });
    }

    private void assertTerminalExpiredRefundPendingState(Fixture fixture) {
        Reservation reservation = reservationRepository.findById(fixture.reservationId()).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId()).orElseThrow();
        PaymentOrder order = paymentOrderRepository.findById(fixture.paymentOrderId()).orElseThrow();
        PaymentAttempt attempt = paymentAttemptRepository.findById(fixture.paymentAttemptId()).orElseThrow();
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.REFUND_PENDING);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PAID);
    }

    private void reconstruct(Long lifecycleId, long lastVersion) {
        for (long version = 1; version <= lastVersion; version++) {
            lifecycleReconstructionService.reconstruct(
                    new LifecycleEventDecisionKey(lifecycleId, version)
            );
        }
    }

    private <T> T inTransaction(Function<Void, T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.apply(null));
    }

    private record Fixture(
            Long reservationId,
            Long paymentOrderId,
            Long seatId,
            Long paymentAttemptId,
            String walletAddress,
            VerifiedPaymentSnapshot verifiedPayment
    ) {
    }
}

package org.example.ticket.lifecycle.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ticket.lifecycle.event.LifecycleActorType;
import org.example.ticket.lifecycle.event.LifecycleEntityType;
import org.example.ticket.lifecycle.event.LifecycleEvent;
import org.example.ticket.lifecycle.event.LifecycleEventDecisionKey;
import org.example.ticket.lifecycle.event.LifecycleEventDecisionReader;
import org.example.ticket.lifecycle.event.LifecycleEventPayload;
import org.example.ticket.lifecycle.event.LifecycleEventRepository;
import org.example.ticket.lifecycle.event.LifecycleEventType;
import org.example.ticket.lifecycle.event.LifecycleStateChange;
import org.example.ticket.payment.constant.PaymentAttemptStatus;
import org.example.ticket.payment.constant.PaymentOrderStatus;
import org.example.ticket.util.constant.ReservationStatus;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({LifecycleEventDecisionReader.class, LifecycleReconstructionService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LifecycleReconstructionJpaTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private LifecycleEventRepository eventRepository;

    @Autowired
    private LifecycleReconstructionService reconstructionService;

    @Autowired
    private LifecycleSnapshotRepository snapshotRepository;

    @Autowired
    private LifecycleSeatSnapshotRepository seatSnapshotRepository;

    @Autowired
    private LifecyclePaymentAttemptSnapshotRepository paymentAttemptSnapshotRepository;

    @Autowired
    private LifecycleEventApplicationRepository applicationRepository;

    @Test
    void reconstructsNormalPathAndDoesNotApplyTheSameDecisionTwice() {
        long lifecycleId = 101L;
        appendNormalEvents(lifecycleId, 11L, 21L, 31L);

        reconstructAll(lifecycleId, 3);
        LifecycleSnapshot snapshot = snapshotRepository.findById(
                new LifecycleProjectionKey(1, lifecycleId)
        ).orElseThrow();

        assertThat(snapshot.getReservationStatus()).isEqualTo(ReservationStatus.SUCCESS.name());
        assertThat(snapshot.getPaymentOrderStatus()).isEqualTo(PaymentOrderStatus.APPLIED.name());
        assertThat(snapshot.getPathClassification()).isEqualTo(LifecyclePathClassification.NORMAL_COMPLETED);
        assertThat(snapshot.getTrustStatus()).isEqualTo(LifecycleTrustStatus.PROCESSING);
        assertThat(snapshot.getLastAppliedVersion()).isEqualTo(3L);
        assertThat(seatSnapshotRepository.findById(new LifecycleSeatSnapshotId(1, lifecycleId, 11L))
                .orElseThrow().getSeatStatus()).isEqualTo(SeatStatus.RESERVED.name());
        assertThat(paymentAttemptSnapshotRepository
                .findById(new LifecyclePaymentAttemptSnapshotId(1, lifecycleId, 31L))
                .orElseThrow().getPaymentAttemptStatus()).isEqualTo(PaymentAttemptStatus.PAID.name());

        LifecycleReconstructionResult duplicate = reconstructionService.reconstruct(
                new LifecycleEventDecisionKey(lifecycleId, 3L)
        );
        assertThat(duplicate.applicationStatus()).isEqualTo(LifecycleApplicationStatus.DUPLICATE);
        assertThat(snapshotRepository.findById(new LifecycleProjectionKey(1, lifecycleId))
                .orElseThrow().getLastAppliedVersion()).isEqualTo(3L);
        assertThat(applicationRepository.count()).isEqualTo(4L);
    }

    @Test
    void distinguishesExpirationFirstAndPaymentHandlerExpiredWithTheSameTerminalState() {
        long expirationFirstId = 201L;
        appendInitialEvents(expirationFirstId, 12L, 22L, 32L);
        appendEvent(expirationFirstId, 3L, 0, LifecycleEventType.RESERVATION_EXPIRED,
                LifecycleActorType.EXPIRATION_SCHEDULER, null, null,
                payload(List.of(12L), List.of(
                        change(LifecycleEntityType.RESERVATION, expirationFirstId,
                                ReservationStatus.PENDING_PAYMENT.name(), ReservationStatus.EXPIRED.name()),
                        change(LifecycleEntityType.SEAT, 12L, SeatStatus.LOCKED.name(), SeatStatus.AVAILABLE.name())
                ), "RESERVATION_EXPIRED_BY_SCHEDULER"));
        appendEvent(expirationFirstId, 4L, 0, LifecycleEventType.PAYMENT_APPROVED,
                LifecycleActorType.PAYMENT_VERIFICATION, 22L, 32L,
                payload(List.of(), List.of(change(LifecycleEntityType.PAYMENT_ATTEMPT, 32L,
                        PaymentAttemptStatus.READY.name(), PaymentAttemptStatus.PAID.name())), "PAYMENT_APPROVED"));
        appendEvent(expirationFirstId, 4L, 1, LifecycleEventType.PAYMENT_REFUND_PENDING,
                LifecycleActorType.PAYMENT_VERIFICATION, 22L, 32L,
                payload(List.of(), List.of(change(LifecycleEntityType.PAYMENT_ORDER, 22L,
                        PaymentOrderStatus.READY.name(), PaymentOrderStatus.REFUND_PENDING.name())),
                        "PAYMENT_APPROVED_AFTER_EXPIRATION"));

        long handlerExpiredId = 301L;
        appendInitialEvents(handlerExpiredId, 13L, 23L, 33L);
        appendEvent(handlerExpiredId, 3L, 0, LifecycleEventType.PAYMENT_APPROVED,
                LifecycleActorType.PAYMENT_VERIFICATION, 23L, 33L,
                payload(List.of(), List.of(change(LifecycleEntityType.PAYMENT_ATTEMPT, 33L,
                        PaymentAttemptStatus.READY.name(), PaymentAttemptStatus.PAID.name())), "PAYMENT_APPROVED"));
        appendEvent(handlerExpiredId, 3L, 1, LifecycleEventType.RESERVATION_EXPIRED,
                LifecycleActorType.PAYMENT_VERIFICATION, 23L, 33L,
                payload(List.of(13L), List.of(
                        change(LifecycleEntityType.RESERVATION, handlerExpiredId,
                                ReservationStatus.PENDING_PAYMENT.name(), ReservationStatus.EXPIRED.name()),
                        change(LifecycleEntityType.SEAT, 13L, SeatStatus.LOCKED.name(), SeatStatus.AVAILABLE.name())
                ), "PAYMENT_DEADLINE_EXCEEDED"));
        appendEvent(handlerExpiredId, 3L, 2, LifecycleEventType.PAYMENT_REFUND_PENDING,
                LifecycleActorType.PAYMENT_VERIFICATION, 23L, 33L,
                payload(List.of(), List.of(change(LifecycleEntityType.PAYMENT_ORDER, 23L,
                        PaymentOrderStatus.READY.name(), PaymentOrderStatus.REFUND_PENDING.name())),
                        "PAYMENT_APPROVED_AFTER_EXPIRATION"));

        reconstructAll(expirationFirstId, 4);
        reconstructAll(handlerExpiredId, 3);

        LifecycleSnapshot first = snapshotRepository.findById(new LifecycleProjectionKey(1, expirationFirstId))
                .orElseThrow();
        LifecycleSnapshot handler = snapshotRepository.findById(new LifecycleProjectionKey(1, handlerExpiredId))
                .orElseThrow();
        assertThat(first.getPathClassification()).isEqualTo(LifecyclePathClassification.EXPIRATION_FIRST);
        assertThat(handler.getPathClassification()).isEqualTo(LifecyclePathClassification.PAYMENT_HANDLER_EXPIRED);
        assertThat(first.getReservationStatus()).isEqualTo(handler.getReservationStatus());
        assertThat(first.getPaymentOrderStatus()).isEqualTo(handler.getPaymentOrderStatus());
        assertThat(seatSnapshotRepository.findById(new LifecycleSeatSnapshotId(1, expirationFirstId, 12L))
                .orElseThrow().getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE.name());
        assertThat(seatSnapshotRepository.findById(new LifecycleSeatSnapshotId(1, handlerExpiredId, 13L))
                .orElseThrow().getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE.name());
    }

    @Test
    void holdsOutOfOrderDecisionUntilTheMissingVersionArrives() {
        long lifecycleId = 401L;
        appendNormalEvents(lifecycleId, 14L, 24L, 34L);

        LifecycleReconstructionResult pending = reconstructionService.reconstruct(
                new LifecycleEventDecisionKey(lifecycleId, 3L)
        );
        assertThat(pending.applicationStatus()).isEqualTo(LifecycleApplicationStatus.PENDING);
        assertThat(snapshotRepository.findById(new LifecycleProjectionKey(1, lifecycleId))
                .orElseThrow().getLastAppliedVersion()).isZero();

        reconstructionService.reconstruct(new LifecycleEventDecisionKey(lifecycleId, 1L));
        reconstructionService.reconstruct(new LifecycleEventDecisionKey(lifecycleId, 2L));
        reconstructionService.reconstruct(new LifecycleEventDecisionKey(lifecycleId, 3L));

        LifecycleSnapshot snapshot = snapshotRepository.findById(new LifecycleProjectionKey(1, lifecycleId))
                .orElseThrow();
        assertThat(snapshot.getLastAppliedVersion()).isEqualTo(3L);
        assertThat(snapshot.getReservationStatus()).isEqualTo(ReservationStatus.SUCCESS.name());
        assertThat(snapshot.getPaymentOrderStatus()).isEqualTo(PaymentOrderStatus.APPLIED.name());
    }

    @Test
    void classifiesExpirationWithoutPayment() {
        long lifecycleId = 501L;
        appendInitialEvents(lifecycleId, 15L, 25L, 35L);
        appendEvent(lifecycleId, 3L, 0, LifecycleEventType.RESERVATION_EXPIRED,
                LifecycleActorType.EXPIRATION_SCHEDULER, null, null,
                payload(List.of(15L), List.of(
                        change(LifecycleEntityType.RESERVATION, lifecycleId,
                                ReservationStatus.PENDING_PAYMENT.name(), ReservationStatus.EXPIRED.name()),
                        change(LifecycleEntityType.SEAT, 15L,
                                SeatStatus.LOCKED.name(), SeatStatus.AVAILABLE.name())
                ), "RESERVATION_EXPIRED_BY_SCHEDULER"));

        reconstructAll(lifecycleId, 3);

        LifecycleSnapshot snapshot = snapshotRepository.findById(new LifecycleProjectionKey(1, lifecycleId))
                .orElseThrow();
        assertThat(snapshot.getReservationStatus()).isEqualTo(ReservationStatus.EXPIRED.name());
        assertThat(snapshot.getPathClassification()).isEqualTo(LifecyclePathClassification.EXPIRED_WITHOUT_PAYMENT);
    }

    @Test
    void rejectsAnEventWhoseTargetStateBreaksTheContract() {
        long lifecycleId = 601L;
        appendEvent(lifecycleId, 1L, 0, LifecycleEventType.RESERVATION_CREATED,
                LifecycleActorType.RESERVATION_SERVICE, null, null,
                payload(List.of(), List.of(change(LifecycleEntityType.RESERVATION, lifecycleId,
                        null, ReservationStatus.SUCCESS.name())), "INVALID"));

        LifecycleReconstructionResult result = reconstructionService.reconstruct(
                new LifecycleEventDecisionKey(lifecycleId, 1L)
        );
        assertThat(result.applicationStatus()).isEqualTo(LifecycleApplicationStatus.FAILED);
        assertThat(result.trustStatus()).isEqualTo(LifecycleTrustStatus.MISMATCH);
        LifecycleSnapshot snapshot = snapshotRepository.findById(new LifecycleProjectionKey(1, lifecycleId))
                .orElseThrow();
        assertThat(snapshot.getLastAppliedVersion()).isZero();
        assertThat(applicationRepository.findAll().stream()
                .filter(application -> application.getLifecycleId().equals(lifecycleId))
                .toList()).singleElement()
                .satisfies(application -> {
                    assertThat(application.getStatus()).isEqualTo(LifecycleApplicationStatus.FAILED);
                    assertThat(application.getErrorType()).isEqualTo(LifecycleApplicationErrorType.CONTRACT);
                    assertThat(application.getErrorCode()).isEqualTo("CONTRACT_EVENT");
                    assertThat(application.getAttemptCount()).isEqualTo(1);
                });

        LifecycleReconstructionResult repeated = reconstructionService.reconstruct(
                new LifecycleEventDecisionKey(lifecycleId, 1L)
        );
        assertThat(repeated.applicationStatus()).isEqualTo(LifecycleApplicationStatus.FAILED);
        assertThat(applicationRepository.findAll().stream()
                .filter(application -> application.getLifecycleId().equals(lifecycleId))
                .toList()).singleElement()
                .extracting(LifecycleEventApplication::getAttemptCount)
                .isEqualTo(1);
    }

    private void reconstructAll(long lifecycleId, int lastVersion) {
        for (long version = 1; version <= lastVersion; version++) {
            reconstructionService.reconstruct(new LifecycleEventDecisionKey(lifecycleId, version));
        }
    }

    private void appendNormalEvents(long lifecycleId, long seatId, long orderId, long attemptId) {
        appendInitialEvents(lifecycleId, seatId, orderId, attemptId);
        appendEvent(lifecycleId, 3L, 0, LifecycleEventType.PAYMENT_APPROVED,
                LifecycleActorType.PAYMENT_VERIFICATION, orderId, attemptId,
                payload(List.of(), List.of(change(LifecycleEntityType.PAYMENT_ATTEMPT, attemptId,
                        PaymentAttemptStatus.READY.name(), PaymentAttemptStatus.PAID.name())), "PAYMENT_APPROVED"));
        appendEvent(lifecycleId, 3L, 1, LifecycleEventType.RESERVATION_COMPLETED,
                LifecycleActorType.PAYMENT_VERIFICATION, orderId, attemptId,
                payload(List.of(seatId), List.of(
                        change(LifecycleEntityType.RESERVATION, lifecycleId,
                                ReservationStatus.PENDING_PAYMENT.name(), ReservationStatus.SUCCESS.name()),
                        change(LifecycleEntityType.SEAT, seatId,
                                SeatStatus.LOCKED.name(), SeatStatus.RESERVED.name()),
                        change(LifecycleEntityType.PAYMENT_ORDER, orderId,
                                PaymentOrderStatus.READY.name(), PaymentOrderStatus.APPLIED.name())
                ), "PAYMENT_APPLIED_TO_RESERVATION"));
    }

    private void appendInitialEvents(long lifecycleId, long seatId, long orderId, long attemptId) {
        appendEvent(lifecycleId, 1L, 0, LifecycleEventType.RESERVATION_CREATED,
                LifecycleActorType.RESERVATION_SERVICE, null, null,
                payload(List.of(seatId), List.of(
                        change(LifecycleEntityType.RESERVATION, lifecycleId,
                                null, ReservationStatus.PENDING_PAYMENT.name()),
                        change(LifecycleEntityType.SEAT, seatId,
                                SeatStatus.AVAILABLE.name(), SeatStatus.LOCKED.name())
                ), "RESERVATION_CREATED"));
        appendEvent(lifecycleId, 2L, 0, LifecycleEventType.PAYMENT_PREPARED,
                LifecycleActorType.PAYMENT_PREPARATION, orderId, attemptId,
                payload(List.of(), List.of(
                        change(LifecycleEntityType.PAYMENT_ORDER, orderId,
                                null, PaymentOrderStatus.READY.name()),
                        change(LifecycleEntityType.PAYMENT_ATTEMPT, attemptId,
                                null, PaymentAttemptStatus.READY.name())
                ), "PAYMENT_PREPARED"));
    }

    private void appendEvent(
            long lifecycleId,
            long decisionVersion,
            int ordinal,
            LifecycleEventType eventType,
            LifecycleActorType actorType,
            Long paymentOrderId,
            Long paymentAttemptId,
            LifecycleEventPayload payload
    ) {
        eventRepository.saveAndFlush(LifecycleEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType.wireValue())
                .schemaVersion(1)
                .lifecycleId(lifecycleId)
                .paymentOrderId(paymentOrderId)
                .paymentAttemptId(paymentAttemptId)
                .decisionVersion(decisionVersion)
                .eventOrdinal(ordinal)
                .commitGroupId("group-" + lifecycleId + "-" + decisionVersion)
                .actorType(actorType.name())
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
}

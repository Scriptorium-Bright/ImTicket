package org.example.ticket.lifecycle.event;

import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.repository.ReservationRepository;
import org.example.ticket.util.constant.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "lifecycle.tracing.event-writer.enabled=true")
@Import({LifecycleEventWriter.class, LifecycleEventDecisionReader.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LifecycleEventWriterJpaTest {

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private LifecycleEventRepository lifecycleEventRepository;

    @Autowired
    private LifecycleEventWriter lifecycleEventWriter;

    @Autowired
    private LifecycleEventDecisionReader lifecycleEventDecisionReader;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void storesReservationCreatedWithTheSameCommittedTransactionAsReservation() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            Reservation reservation = reservationRepository.saveAndFlush(newReservation("reservation-event-1"));

            lifecycleEventWriter.recordReservationCreated(reservation, reservationCreatedDraft(reservation));
            lifecycleEventWriter.recordDecision(reservation, List.of(
                    paymentPreparedDraft(),
                    paymentApprovedDraft()
            ));
            reservationRepository.flush();

            assertThat(reservation.getLifecycleVersion()).isEqualTo(2L);
            List<LifecycleEvent> events = lifecycleEventRepository
                    .findByLifecycleIdOrderByDecisionVersionAscEventOrdinalAsc(reservation.getId());
            assertThat(events).extracting(LifecycleEvent::getEventType)
                    .containsExactly("ReservationCreated", "PaymentPrepared", "PaymentApproved");
            assertThat(events).extracting(LifecycleEvent::getDecisionVersion)
                    .containsExactly(1L, 2L, 2L);
            assertThat(events).extracting(LifecycleEvent::getEventOrdinal)
                    .containsExactly(0, 0, 1);
            assertThat(events).extracting(LifecycleEvent::getCommitGroupId)
                    .containsOnly(events.getFirst().getCommitGroupId());
            assertThat(events.getFirst().getPayload()).contains("RESERVATION_CREATED");
            assertThat(lifecycleEventDecisionReader.findDecisionKeys(org.springframework.data.domain.PageRequest.of(0, 10)))
                    .containsExactly(
                            new LifecycleEventDecisionKey(reservation.getId(), 1L),
                            new LifecycleEventDecisionKey(reservation.getId(), 2L)
                    );
            assertThat(lifecycleEventDecisionReader.readDecision(
                    new LifecycleEventDecisionKey(reservation.getId(), 2L)
            )).extracting(LifecycleEvent::getEventType)
                    .containsExactly("PaymentPrepared", "PaymentApproved");
        });
    }

    @Test
    void rollsBackLifecycleEventTogetherWithReservation() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            Reservation reservation = reservationRepository.saveAndFlush(newReservation("reservation-event-rollback"));
            lifecycleEventWriter.recordReservationCreated(reservation, reservationCreatedDraft(reservation));
            status.setRollbackOnly();
        });

        assertThat(reservationRepository.findAll()).isEmpty();
        assertThat(lifecycleEventRepository.findAll()).isEmpty();
    }

    @Test
    void leavesLegacyReservationUntrackedWithoutCreatingAPartialTimeline() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Long reservationId = transaction.execute(status -> {
            Reservation reservation = reservationRepository.saveAndFlush(newReservation("reservation-event-legacy"));
            lifecycleEventWriter.recordDecision(reservation, List.of(paymentPreparedDraft()));
            return reservation.getId();
        });

        Reservation persisted = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(persisted.getLifecycleVersion()).isZero();
        assertThat(lifecycleEventRepository.findByLifecycleIdOrderByDecisionVersionAscEventOrdinalAsc(reservationId))
                .isEmpty();
    }

    private Reservation newReservation(String reservationCode) {
        return Reservation.builder()
                .reservationCode(reservationCode)
                .totalPrice(10_000)
                .reservationStatus(ReservationStatus.PENDING_PAYMENT)
                .build();
    }

    private LifecycleEventDraft reservationCreatedDraft(Reservation reservation) {
        return new LifecycleEventDraft(
                LifecycleEventType.RESERVATION_CREATED,
                LifecycleActorType.RESERVATION_SERVICE,
                null,
                null,
                new LifecycleEventPayload(
                        List.of(),
                        List.of(LifecycleStateChange.created(
                                LifecycleEntityType.RESERVATION,
                                reservation.getId(),
                                ReservationStatus.PENDING_PAYMENT.name()
                        )),
                        "RESERVATION_CREATED"
                )
        );
    }

    private LifecycleEventDraft paymentPreparedDraft() {
        return new LifecycleEventDraft(
                LifecycleEventType.PAYMENT_PREPARED,
                LifecycleActorType.PAYMENT_PREPARATION,
                null,
                null,
                new LifecycleEventPayload(List.of(), List.of(), "PAYMENT_PREPARED")
        );
    }

    private LifecycleEventDraft paymentApprovedDraft() {
        return new LifecycleEventDraft(
                LifecycleEventType.PAYMENT_APPROVED,
                LifecycleActorType.PAYMENT_VERIFICATION,
                null,
                null,
                new LifecycleEventPayload(List.of(), List.of(), "PAYMENT_APPROVED")
        );
    }
}

package org.example.ticket.reservation.booking.concurrency;

import org.example.ticket.reservation.booking.api.ReservationRequest;
import org.example.ticket.reservation.booking.application.ReservationService;
import org.example.ticket.reservation.booking.application.ReservationIdempotentCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationLockAnnotationTest {

    @Test
    void marksReservationEntryPointExplicitly() throws NoSuchMethodException {
        Method method = ReservationService.class.getMethod(
                "createReservation",
                String.class,
                ReservationRequest.class
        );

        ReservationLock annotation = method.getAnnotation(ReservationLock.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.strategy()).isEqualTo(ReservationLockStrategy.CONFIGURED);
    }

    @Test
    void idempotentCreationOwnsLockUntilItsTransactionCommits() throws NoSuchMethodException {
        Method outerMethod = ReservationIdempotentCreationService.class.getMethod(
                "create",
                Long.class,
                String.class,
                ReservationRequest.class,
                String.class,
                Long.class,
                String.class
        );
        ReservationLock lock = outerMethod.getAnnotation(ReservationLock.class);
        Transactional transaction = outerMethod.getAnnotation(Transactional.class);

        assertThat(lock).isNotNull();
        assertThat(lock.strategy()).isEqualTo(ReservationLockStrategy.CONFIGURED);
        assertThat(transaction).isNotNull();
    }

    @Test
    void innerReservationMutationRequiresExistingOuterTransaction() throws NoSuchMethodException {
        Method innerMethod = ReservationService.class.getMethod(
                "createReservationWithinTransaction",
                String.class,
                ReservationRequest.class
        );
        Transactional transaction = innerMethod.getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(innerMethod.getAnnotation(ReservationLock.class)).isNull();
    }
}

package org.example.ticket.reservation.lock;

import org.example.ticket.reservation.request.ReservationRequest;
import org.example.ticket.reservation.service.ReservationService;
import org.junit.jupiter.api.Test;

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
}

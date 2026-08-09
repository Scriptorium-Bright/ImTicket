package org.example.ticket.reservation.booking.persistence;

import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationRepositoryLockPolicyTest {

    @Test
    void singleReservationLockUsesPessimisticWrite() throws NoSuchMethodException {
        assertPessimisticWrite(ReservationRepository.class.getMethod(
                "findByIdForUpdate",
                Long.class
        ));
    }

    @Test
    void orderedReservationBatchLockUsesPessimisticWrite() throws NoSuchMethodException {
        assertPessimisticWrite(ReservationRepository.class.getMethod(
                "findByIdInForUpdate",
                List.class
        ));
    }

    private void assertPessimisticWrite(Method method) {
        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }
}

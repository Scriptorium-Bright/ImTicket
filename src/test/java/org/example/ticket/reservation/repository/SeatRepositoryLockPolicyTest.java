package org.example.ticket.reservation.repository;

import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeatRepositoryLockPolicyTest {

    @Test
    void findSeatsForUpdateHasPessimisticWriteAndLockTimeout() throws NoSuchMethodException {
        Method method = SeatRepository.class.getMethod(
                "findByPerformanceTimeIdAndIdsForUpdate",
                Long.class,
                List.class
        );

        Lock lock = method.getAnnotation(Lock.class);
        QueryHints queryHints = method.getAnnotation(QueryHints.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(queryHints).isNotNull();
        assertThat(Arrays.stream(queryHints.value()))
                .anySatisfy(queryHint -> {
                    assertThat(queryHint.name()).isEqualTo("jakarta.persistence.lock.timeout");
                    assertThat(queryHint.value()).isEqualTo("3000");
                });
    }
}

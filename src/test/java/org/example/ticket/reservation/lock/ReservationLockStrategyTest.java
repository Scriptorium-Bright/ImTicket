package org.example.ticket.reservation.lock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationLockStrategyTest {

    @Test
    void acceptsConfiguredStrategyNamesAndAliases() {
        assertThat(ReservationLockStrategy.from("pessimistic"))
                .isEqualTo(ReservationLockStrategy.PESSIMISTIC);
        assertThat(ReservationLockStrategy.from("synchronized"))
                .isEqualTo(ReservationLockStrategy.SYNCHRONIZED);
        assertThat(ReservationLockStrategy.from("reentrant"))
                .isEqualTo(ReservationLockStrategy.REENTRANT);
        assertThat(ReservationLockStrategy.from("optimistic"))
                .isEqualTo(ReservationLockStrategy.OPTIMISTIC);
        assertThat(ReservationLockStrategy.from("single-thread"))
                .isEqualTo(ReservationLockStrategy.SINGLE_THREAD);
        assertThat(ReservationLockStrategy.from("named"))
                .isEqualTo(ReservationLockStrategy.MYSQL_NAMED);
        assertThat(ReservationLockStrategy.from("advisory"))
                .isEqualTo(ReservationLockStrategy.MYSQL_NAMED);
    }

    @Test
    void defaultsToPessimisticStrategy() {
        assertThat(ReservationLockStrategy.from(null))
                .isEqualTo(ReservationLockStrategy.PESSIMISTIC);
        assertThat(ReservationLockStrategy.from(""))
                .isEqualTo(ReservationLockStrategy.PESSIMISTIC);
    }

    @Test
    void rejectsConfiguredMarkerAsExternalConfiguration() {
        assertThatThrownBy(() -> ReservationLockStrategy.from("configured"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("실제 전략을 지정해야 합니다");
    }
}

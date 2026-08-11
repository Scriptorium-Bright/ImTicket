package org.example.ticket.reservation.common.policy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationProcessingLeasePolicyTest {

    @Test
    void acceptsClaimLeaseEqualToOrLongerThanQueueLease() {
        ReservationProcessingLeasePolicy equal = new ReservationProcessingLeasePolicy(
                Duration.ofSeconds(30),
                Duration.ofSeconds(30)
        );
        ReservationProcessingLeasePolicy longer = new ReservationProcessingLeasePolicy(
                Duration.ofSeconds(60),
                Duration.ofSeconds(30)
        );

        assertThat(equal.claimLease()).isEqualTo(Duration.ofSeconds(30));
        assertThat(longer.claimLease()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void rejectsClaimLeaseShorterThanQueueLease() {
        assertThatThrownBy(() -> new ReservationProcessingLeasePolicy(
                Duration.ofSeconds(29),
                Duration.ofSeconds(30)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claimLease");
    }
}

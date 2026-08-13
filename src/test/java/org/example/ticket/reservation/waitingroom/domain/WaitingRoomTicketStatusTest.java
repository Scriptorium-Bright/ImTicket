package org.example.ticket.reservation.waitingroom.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WaitingRoomTicketStatusTest {

    /** WAITING과 ADMITTED의 허용 전이를 lifecycle contract로 검증한다. */
    @Test
    void allowsOnlyForwardLifecycleTransitions() {
        assertThat(WaitingRoomTicketStatus.WAITING.canTransitionTo(WaitingRoomTicketStatus.ADMITTED)).isTrue();
        assertThat(WaitingRoomTicketStatus.WAITING.canTransitionTo(WaitingRoomTicketStatus.CANCELED)).isTrue();
        assertThat(WaitingRoomTicketStatus.ADMITTED.canTransitionTo(WaitingRoomTicketStatus.COMPLETED)).isTrue();
        assertThat(WaitingRoomTicketStatus.ADMITTED.canTransitionTo(WaitingRoomTicketStatus.EXPIRED)).isTrue();
        assertThat(WaitingRoomTicketStatus.COMPLETED.canTransitionTo(WaitingRoomTicketStatus.WAITING)).isFalse();
        assertThat(WaitingRoomTicketStatus.CANCELED.canTransitionTo(WaitingRoomTicketStatus.ADMITTED)).isFalse();
    }

    /** terminal 상태가 재전이를 허용하지 않는지 검증한다. */
    @Test
    void identifiesTerminalStates() {
        assertThat(WaitingRoomTicketStatus.WAITING.isTerminal()).isFalse();
        assertThat(WaitingRoomTicketStatus.ADMITTED.isTerminal()).isFalse();
        assertThat(WaitingRoomTicketStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(WaitingRoomTicketStatus.CANCELED.isTerminal()).isTrue();
        assertThat(WaitingRoomTicketStatus.EXPIRED.isTerminal()).isTrue();
    }
}

package org.example.ticket.reservation.queue.constant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationQueueStatusTest {

    @Test
    void waitingAndProcessingExposeOnlyAllowedTransitions() {
        assertThat(ReservationQueueStatus.WAITING.canTransitionTo(ReservationQueueStatus.PROCESSING)).isTrue();
        assertThat(ReservationQueueStatus.WAITING.canTransitionTo(ReservationQueueStatus.EXPIRED)).isTrue();
        assertThat(ReservationQueueStatus.WAITING.canTransitionTo(ReservationQueueStatus.SUCCEEDED)).isFalse();

        assertThat(ReservationQueueStatus.PROCESSING.canTransitionTo(ReservationQueueStatus.SUCCEEDED)).isTrue();
        assertThat(ReservationQueueStatus.PROCESSING.canTransitionTo(ReservationQueueStatus.FAILED_FINAL)).isTrue();
        assertThat(ReservationQueueStatus.PROCESSING.canTransitionTo(ReservationQueueStatus.RETRY_WAIT)).isTrue();
        assertThat(ReservationQueueStatus.PROCESSING.canTransitionTo(ReservationQueueStatus.WAITING)).isFalse();
    }

    @Test
    void retryWaitIsPresentedAsWaitingAndCanBeRequeuedOrExpired() {
        assertThat(ReservationQueueStatus.RETRY_WAIT.visibleStatus()).isEqualTo(ReservationQueueStatus.WAITING);
        assertThat(ReservationQueueStatus.RETRY_WAIT.canTransitionTo(ReservationQueueStatus.WAITING)).isTrue();
        assertThat(ReservationQueueStatus.RETRY_WAIT.canTransitionTo(ReservationQueueStatus.EXPIRED)).isTrue();
    }

    @Test
    void completedStatusesAreTerminal() {
        assertThat(ReservationQueueStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(ReservationQueueStatus.FAILED_FINAL.isTerminal()).isTrue();
        assertThat(ReservationQueueStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(ReservationQueueStatus.WAITING.isTerminal()).isFalse();
        assertThat(ReservationQueueStatus.SUCCEEDED.canTransitionTo(ReservationQueueStatus.WAITING)).isFalse();
    }
}

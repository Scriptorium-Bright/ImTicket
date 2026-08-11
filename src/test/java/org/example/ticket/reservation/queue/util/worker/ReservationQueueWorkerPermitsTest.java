package org.example.ticket.reservation.queue.util.worker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationQueueWorkerPermitsTest {

    @Test
    void appliesGlobalAndPerPerformanceLimitsBeforeWorkStarts() {
        ReservationQueueWorkerPermits permits = new ReservationQueueWorkerPermits(2, 1);

        ReservationQueueWorkerPermits.Permit first = permits.tryAcquire(10L).orElseThrow();
        assertThat(permits.tryAcquire(10L)).isEmpty();
        ReservationQueueWorkerPermits.Permit second = permits.tryAcquire(20L).orElseThrow();
        assertThat(permits.tryAcquire(30L)).isEmpty();

        first.close();
        ReservationQueueWorkerPermits.Permit replacement = permits.tryAcquire(10L).orElseThrow();
        replacement.close();
        second.close();
        assertThat(permits.availableGlobalPermits()).isEqualTo(2);
    }

    @Test
    void permitCloseIsIdempotent() {
        ReservationQueueWorkerPermits permits = new ReservationQueueWorkerPermits(1, 1);
        ReservationQueueWorkerPermits.Permit permit = permits.tryAcquire(10L).orElseThrow();

        permit.close();
        permit.close();

        assertThat(permits.availableGlobalPermits()).isEqualTo(1);
    }
}

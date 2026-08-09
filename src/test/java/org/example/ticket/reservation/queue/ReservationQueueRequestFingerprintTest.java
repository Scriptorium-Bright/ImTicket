package org.example.ticket.reservation.queue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationQueueRequestFingerprintTest {

    @Test
    void seatOrderDoesNotChangeRequestHash() {
        ReservationQueueRequestFingerprint first = ReservationQueueRequestFingerprint.of(10L, List.of(3L, 1L));
        ReservationQueueRequestFingerprint second = ReservationQueueRequestFingerprint.of(10L, List.of(1L, 3L));

        assertThat(first.requestHash()).isEqualTo(second.requestHash()).matches("[0-9a-f]{64}");
        assertThat(first.normalizedSeatIds()).containsExactly(1L, 3L);
        assertThat(first.performanceTimeId()).isEqualTo(10L);
    }

    @Test
    void performanceAndSeatSelectionArePartOfRequestIdentity() {
        String baseline = ReservationQueueRequestFingerprint.of(10L, List.of(1L, 3L)).requestHash();

        assertThat(ReservationQueueRequestFingerprint.of(11L, List.of(1L, 3L)).requestHash())
                .isNotEqualTo(baseline);
        assertThat(ReservationQueueRequestFingerprint.of(10L, List.of(1L, 4L)).requestHash())
                .isNotEqualTo(baseline);
    }

    @Test
    void rejectsInvalidPerformanceAndSeatIds() {
        assertThatThrownBy(() -> ReservationQueueRequestFingerprint.of(0L, List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReservationQueueRequestFingerprint.of(10L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReservationQueueRequestFingerprint.of(10L, Arrays.asList(1L, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReservationQueueRequestFingerprint.of(10L, List.of(1L, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReservationQueueRequestFingerprint.of(10L, List.of(-1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

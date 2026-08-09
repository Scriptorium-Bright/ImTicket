package org.example.ticket.reservation.queue.dto;

import org.example.ticket.reservation.common.domain.ReservationIdempotencyKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationQueuePayloadTest {

    private static final String KEY = "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1";

    @Test
    void createsImmutableCurrentWorkerPayload() {
        ArrayList<Long> seatIds = new ArrayList<>(List.of(1L, 3L));

        ReservationQueuePayload payload = ReservationQueuePayload.current(
                42L,
                ReservationIdempotencyKey.from(KEY),
                "c".repeat(64),
                seatIds
        );
        seatIds.add(5L);

        assertThat(payload.schemaVersion()).isEqualTo(1);
        assertThat(payload.memberId()).isEqualTo(42L);
        assertThat(payload.idempotencyKey().value()).isEqualTo(KEY);
        assertThat(payload.normalizedSeatIds()).containsExactly(1L, 3L);
        assertThatThrownBy(() -> payload.normalizedSeatIds().add(5L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnsupportedSchemaMemberAndSeatOrder() {
        ReservationIdempotencyKey key = ReservationIdempotencyKey.from(KEY);

        assertThatThrownBy(() -> new ReservationQueuePayload(2, 42L, key, "c".repeat(64), List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
        assertThatThrownBy(() -> ReservationQueuePayload.current(0L, key, "c".repeat(64), List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memberId");
        assertThatThrownBy(() -> ReservationQueuePayload.current(42L, key, "c".repeat(64), List.of(3L, 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sorted");
    }
}

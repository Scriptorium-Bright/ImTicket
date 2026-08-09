package org.example.ticket.reservation.common.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationIdempotencyKeyTest {

    private static final String KEY = "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1";

    @Test
    void canonicalizesClientUuidAndProvidesItsHash() {
        ReservationIdempotencyKey key = ReservationIdempotencyKey.from(KEY.toUpperCase());

        assertThat(key.value()).isEqualTo(KEY);
        assertThat(key.hash()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsStoredKeyAndHashMismatch() {
        assertThatThrownBy(() -> ReservationIdempotencyKey.restore(KEY, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }
}

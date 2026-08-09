package org.example.ticket.reservation.queue.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationQueueIdentityHasherTest {

    private final ReservationQueueIdentityHasher hasher = new ReservationQueueIdentityHasher();

    @Test
    void walletHashIgnoresSurroundingSpacesAndLetterCase() {
        assertThat(hasher.ownerHash("  0xAbCdEf  "))
                .isEqualTo(hasher.ownerHash("0xabcdef"))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void idempotencyHashUsesCanonicalUuid() {
        assertThat(hasher.idempotencyKeyHash("A0EBC4C9-8D82-47AF-8127-1FC3D27E47A1"))
                .isEqualTo(hasher.idempotencyKeyHash("a0ebc4c9-8d82-47af-8127-1fc3d27e47a1"))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsMissingWalletAndNonCanonicalIdempotencyKey() {
        assertThatThrownBy(() -> hasher.ownerHash(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.idempotencyKeyHash("retry-key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.idempotencyKeyHash("1-1-1-1-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

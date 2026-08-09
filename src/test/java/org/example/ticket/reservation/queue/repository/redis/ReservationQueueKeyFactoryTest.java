package org.example.ticket.reservation.queue.repository.redis;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationQueueKeyFactoryTest {

    private final ReservationQueueKeyFactory keyFactory = new ReservationQueueKeyFactory();

    @Test
    void performanceScopedKeysShareOneRedisClusterHashTag() {
        long performanceTimeId = 42L;
        UUID ticketId = UUID.fromString("3db65e53-7878-43dc-9a7e-718568771a06");

        assertThat(keyFactory.stream(performanceTimeId)).isEqualTo("reservation:queue:{42}:stream");
        assertThat(keyFactory.admitted(performanceTimeId)).contains("{42}");
        assertThat(keyFactory.waiting(performanceTimeId)).contains("{42}");
        assertThat(keyFactory.deadline(performanceTimeId)).contains("{42}");
        assertThat(keyFactory.sequence(performanceTimeId)).contains("{42}");
        assertThat(keyFactory.ticket(performanceTimeId, ticketId)).contains("{42}");
    }

    @Test
    void idempotencyKeyUsesOnlyHashedIdentityAndHasNoPerformanceScope() {
        String ownerHash = "a".repeat(64);
        String keyHash = "b".repeat(64);

        assertThat(keyFactory.idempotency(ownerHash, keyHash))
                .isEqualTo("reservation:queue:idempotency:" + ownerHash + ":" + keyHash)
                .doesNotContain("{42}");
        assertThat(keyFactory.activePerformanceTimes()).isEqualTo("reservation:queue:active-performance-times");
    }

    @Test
    void rejectsInvalidPerformanceAndUnhashedIdentity() {
        assertThatThrownBy(() -> keyFactory.stream(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> keyFactory.idempotency("0xRawWallet", "retry-key"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

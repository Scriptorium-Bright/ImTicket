package org.example.ticket.reservation.queue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationQueueAdmissionCommandTest {

    @Test
    void acceptsNormalizedQueueRequest() {
        ReservationQueueAdmissionCommand command = command(List.of(1L, 3L));

        assertThat(command.serializedSeatIds()).isEqualTo("1,3");
        assertThat(command.deadline(ReservationQueueProperties.defaults()))
                .isEqualTo(Instant.parse("2026-08-10T10:10:00Z"));
    }

    @Test
    void rejectsUnsortedSeatsAndRawIdentity() {
        assertThatThrownBy(() -> command(List.of(3L, 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sorted");

        assertThatThrownBy(() -> new ReservationQueueAdmissionCommand(
                42L,
                UUID.fromString("f76f5ac8-a475-4e04-906a-1f54765f9770"),
                "0xRawWallet",
                "b".repeat(64),
                "c".repeat(64),
                List.of(1L),
                Instant.parse("2026-08-10T10:00:00Z")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerHash");
    }

    private ReservationQueueAdmissionCommand command(List<Long> seatIds) {
        return new ReservationQueueAdmissionCommand(
                42L,
                UUID.fromString("f76f5ac8-a475-4e04-906a-1f54765f9770"),
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                seatIds,
                Instant.parse("2026-08-10T10:00:00Z")
        );
    }
}

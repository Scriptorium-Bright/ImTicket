package org.example.ticket.reservation.queue.dto;

import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.junit.jupiter.api.Test;
import org.example.ticket.reservation.common.value.ReservationIdempotencyKey;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationQueueAdmissionCommandTest {

    @Test
    void acceptsNormalizedQueueRequest() {
        ReservationQueueAdmissionCommand command = command(List.of(1L, 3L));

        assertThat(command.payload().serializedSeatIds()).isEqualTo("1,3");
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
                payload(List.of(1L)),
                Instant.parse("2026-08-10T10:00:00Z")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerHash");
    }

    private ReservationQueueAdmissionCommand command(List<Long> seatIds) {
        return new ReservationQueueAdmissionCommand(
                42L,
                UUID.fromString("f76f5ac8-a475-4e04-906a-1f54765f9770"),
                "a".repeat(64),
                payload(seatIds),
                Instant.parse("2026-08-10T10:00:00Z")
        );
    }

    private ReservationQueuePayload payload(List<Long> seatIds) {
        return ReservationQueuePayload.current(
                42L,
                ReservationIdempotencyKey.from("a0ebc4c9-8d82-47af-8127-1fc3d27e47a1"),
                "c".repeat(64),
                seatIds
        );
    }
}

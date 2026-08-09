package org.example.ticket.reservation.queue.application;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/** Queue POST body다. */
public record ReservationQueueApiRequest(
        @NotNull @Positive Long performanceTimeId,
        @NotEmpty List<@NotNull @Positive Long> seatIds
) {
}

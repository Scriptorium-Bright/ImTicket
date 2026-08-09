package org.example.ticket.reservation.queue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationQueueExpiryServiceTest {

    @Test
    void expiresDueTicketsForEveryActivePerformanceAndCleansStaleIndex() {
        ReservationQueueExpiryIndex expiryIndex = mock(ReservationQueueExpiryIndex.class);
        ReservationQueueTicketStore ticketStore = mock(ReservationQueueTicketStore.class);
        ReservationQueueProperties properties = ReservationQueueProperties.defaults();
        ReservationQueueExpiryService service = new ReservationQueueExpiryService(
                expiryIndex,
                ticketStore,
                properties
        );
        Instant now = Instant.parse("2026-08-10T10:10:00Z");
        UUID first = UUID.fromString("f76f5ac8-a475-4e04-906a-1f54765f9770");
        UUID second = UUID.fromString("e4f31fe2-ce93-4082-b9d1-7904c952bb8d");

        when(expiryIndex.activePerformanceTimeIds()).thenReturn(List.of(42L, 43L));
        when(expiryIndex.dueTicketIds(42L, now, properties.expiryBatchSize()))
                .thenReturn(List.of(first, second));
        when(expiryIndex.dueTicketIds(43L, now, properties.expiryBatchSize()))
                .thenReturn(List.of());
        when(ticketStore.expireIfDue(42L, first, now)).thenReturn(true);
        when(ticketStore.expireIfDue(42L, second, now)).thenReturn(false);

        assertThat(service.expireDue(now)).isEqualTo(1);
        verify(expiryIndex).removeStalePerformanceTimes(now);
    }
}

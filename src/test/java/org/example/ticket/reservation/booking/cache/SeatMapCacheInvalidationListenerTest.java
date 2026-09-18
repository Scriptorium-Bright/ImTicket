package org.example.ticket.reservation.booking.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class SeatMapCacheInvalidationListenerTest {

    @Mock
    private SeatMapCacheStore cacheStore;

    @Mock
    private SeatMapCacheReader cacheReader;

    @Mock
    private SeatMapDatabaseReader databaseReader;

    @Test
    void evictsSnapshotAfterCommitEvent() {
        SeatMapCacheInvalidationListener listener = new SeatMapCacheInvalidationListener(
                cacheStore,
                cacheReader,
                databaseReader,
                new SimpleMeterRegistry()
        );

        listener.invalidate(new SeatMapInvalidationEvent(7L));

        verify(cacheStore).evict(7L);
        verify(cacheReader).resetReadGateAfterInvalidation(new SeatMapInvalidationEvent(7L));
    }

    @Test
    void invalidationFailureDoesNotChangeAlreadyCommittedReservationResult() {
        doThrow(new SeatMapCacheException("redis down", new IllegalStateException()))
                .when(cacheStore)
                .evict(7L);
        SeatMapCacheInvalidationListener listener = new SeatMapCacheInvalidationListener(
                cacheStore,
                cacheReader,
                databaseReader,
                new SimpleMeterRegistry()
        );

        assertThatCode(() -> listener.invalidate(new SeatMapInvalidationEvent(7L)))
                .doesNotThrowAnyException();
    }

    @Test
    void updatesOnlyChangedAvailabilityFieldsAfterCommit() {
        SeatMapInvalidationEvent event = new SeatMapInvalidationEvent(7L, java.util.List.of(11L, 12L));
        java.util.List<SeatAvailabilityCacheEntry> entries = java.util.List.of(
                new SeatAvailabilityCacheEntry(11L, org.example.ticket.util.constant.SeatStatus.LOCKED, 8L),
                new SeatAvailabilityCacheEntry(12L, org.example.ticket.util.constant.SeatStatus.AVAILABLE, 9L)
        );
        org.mockito.Mockito.when(databaseReader.readAvailability(7L, event.seatIds())).thenReturn(entries);
        org.mockito.Mockito.when(cacheStore.updateAvailability(7L, entries)).thenReturn(true);
        SeatMapCacheInvalidationListener listener = new SeatMapCacheInvalidationListener(
                cacheStore,
                cacheReader,
                databaseReader,
                new SimpleMeterRegistry()
        );

        listener.invalidate(event);

        verify(databaseReader).readAvailability(7L, event.seatIds());
        verify(cacheStore).updateAvailability(7L, entries);
        org.mockito.Mockito.verify(cacheStore, org.mockito.Mockito.never()).evict(7L);
        verify(cacheReader).resetReadGateAfterInvalidation(event);
    }
}

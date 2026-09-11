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

    @Test
    void evictsSnapshotAfterCommitEvent() {
        SeatMapCacheInvalidationListener listener = new SeatMapCacheInvalidationListener(
                cacheStore,
                cacheReader,
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
                new SimpleMeterRegistry()
        );

        assertThatCode(() -> listener.invalidate(new SeatMapInvalidationEvent(7L)))
                .doesNotThrowAnyException();
    }
}

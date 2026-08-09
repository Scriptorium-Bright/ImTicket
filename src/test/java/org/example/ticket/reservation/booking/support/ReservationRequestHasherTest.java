package org.example.ticket.reservation.booking.support;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.booking.domain.ReservationErrorCode;
import org.example.ticket.reservation.booking.api.ReservationRequest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationRequestHasherTest {

    private final ReservationRequestHasher hasher = new ReservationRequestHasher();

    @Test
    void sameSeatsInDifferentOrderProduceSameHash() {
        var first = hasher.fingerprint(new ReservationRequest(10L, List.of(3L, 1L)));
        var second = hasher.fingerprint(new ReservationRequest(10L, List.of(1L, 3L)));

        assertThat(first.requestHash()).isEqualTo(second.requestHash());
        assertThat(first.normalizedSeatIds()).containsExactly(1L, 3L);
    }

    @Test
    void differentPerformanceOrSeatsProduceDifferentHash() {
        String baseline = hasher.fingerprint(new ReservationRequest(10L, List.of(1L, 3L))).requestHash();

        assertThat(hasher.fingerprint(new ReservationRequest(11L, List.of(1L, 3L))).requestHash())
                .isNotEqualTo(baseline);
        assertThat(hasher.fingerprint(new ReservationRequest(10L, List.of(1L, 4L))).requestHash())
                .isNotEqualTo(baseline);
    }

    @Test
    void rejectsNullAndDuplicateSeatIdsBeforeClaim() {
        assertThatThrownBy(() -> hasher.fingerprint(
                new ReservationRequest(10L, Arrays.asList(1L, null))
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_SEAT_ID));

        assertThatThrownBy(() -> hasher.fingerprint(
                new ReservationRequest(10L, List.of(1L, 1L))
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ReservationErrorCode.DUPLICATE_SEAT_INCLUDED));
    }

    @Test
    void normalizesCanonicalUuidAndRejectsNonUuidKey() {
        assertThat(hasher.normalizeKey("A0EBC4C9-8D82-47AF-8127-1FC3D27E47A1"))
                .isEqualTo("a0ebc4c9-8d82-47af-8127-1fc3d27e47a1");

        assertThatThrownBy(() -> hasher.normalizeKey("retry-key"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.IDEMPOTENCY_KEY_INVALID));
    }
}

package org.example.ticket.reservation.booking.util;

import jakarta.persistence.LockTimeoutException;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.constant.ReservationFailureType;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationFailureClassifierTest {

    private final ReservationFailureClassifier classifier = new ReservationFailureClassifier();

    @Test
    void classifiesAdmissionAndTransientLockFailuresAsRetryable() {
        assertThat(classifier.classify(
                new BusinessException(ReservationErrorCode.SEAT_ADMISSION_REJECTED)
        )).isEqualTo(ReservationFailureType.RETRYABLE);
        assertThat(classifier.classify(
                new CannotAcquireLockException("lock", new LockTimeoutException())
        )).isEqualTo(ReservationFailureType.RETRYABLE);
    }

    @Test
    void classifiesStableReservationFailuresAsFinal() {
        BusinessException failure = new BusinessException(ReservationErrorCode.SEAT_ALREADY_RESERVED);

        assertThat(classifier.classify(failure)).isEqualTo(ReservationFailureType.FINAL);
        assertThat(classifier.requireFinalErrorCode(failure))
                .isEqualTo(ReservationErrorCode.SEAT_ALREADY_RESERVED);
    }

    @Test
    void leavesUnknownAndClaimCoordinationFailuresLeaseGuarded() {
        assertThat(classifier.classify(new IllegalStateException("unexpected")))
                .isEqualTo(ReservationFailureType.LEASE_GUARDED);
        assertThat(classifier.classify(
                new BusinessException(ReservationErrorCode.IDEMPOTENCY_PROCESSING)
        )).isEqualTo(ReservationFailureType.LEASE_GUARDED);
        assertThatThrownBy(() -> classifier.requireFinalErrorCode(
                new BusinessException(ReservationErrorCode.IDEMPOTENCY_PROCESSING)
        )).isInstanceOf(IllegalArgumentException.class);
    }
}


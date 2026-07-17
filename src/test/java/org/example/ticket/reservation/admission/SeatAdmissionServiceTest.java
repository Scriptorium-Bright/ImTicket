package org.example.ticket.reservation.admission;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.exception.ReservationErrorCode;
import org.example.ticket.reservation.request.ReservationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatAdmissionServiceTest {

    private final SeatAdmissionService admissionService = new SeatAdmissionService(1);

    @Test
    void rejectsSameSeatImmediatelyAndReleasesPermitAfterCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<String> holder = executor.submit(() -> admissionService.execute(request(1L), () -> {
            entered.countDown();
            await(release);
            return "held";
        }));

        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> admissionService.execute(request(1L), () -> "must not execute"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(error -> ((BusinessException) error).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SEAT_ADMISSION_REJECTED);

            release.countDown();
            assertThat(holder.get(1, TimeUnit.SECONDS)).isEqualTo("held");
            assertThat(admissionService.execute(request(1L), () -> "re-admitted")).isEqualTo("re-admitted");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void releasesPermitWhenReservationOperationThrows() {
        assertThatThrownBy(() -> admissionService.execute(request(2L), () -> {
            throw new IllegalStateException("reservation failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(admissionService.execute(request(2L), () -> "re-admitted"))
                .isEqualTo("re-admitted");
    }

    @Test
    void admitsDifferentSeatsIndependently() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> holder = executor.submit(() -> admissionService.execute(request(3L), () -> {
            entered.countDown();
            await(release);
            return "seat-3";
        }));

        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(admissionService.execute(request(4L), () -> "seat-4")).isEqualTo("seat-4");
        } finally {
            release.countDown();
            assertThat(holder.get(1, TimeUnit.SECONDS)).isEqualTo("seat-3");
            executor.shutdownNow();
        }
    }

    @Test
    void releasesAlreadyAcquiredSeatsWhenMultiSeatAdmissionIsRejected() {
        try (SeatAdmissionService.SeatAdmission ignored = admissionService.admit(request(6L))) {
            assertThatThrownBy(() -> admissionService.admit(new ReservationRequest(1L, List.of(5L, 6L))))
                    .isInstanceOf(BusinessException.class)
                    .extracting(error -> ((BusinessException) error).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SEAT_ADMISSION_REJECTED);

            try (SeatAdmissionService.SeatAdmission released = admissionService.admit(request(5L))) {
                assertThat(released).isNotNull();
            }
        }
    }

    private ReservationRequest request(Long seatId) {
        return new ReservationRequest(1L, List.of(seatId));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("holder release를 기다리다 시간 초과했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}

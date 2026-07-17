package org.example.ticket.reservation.controller;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.common.exception.GlobalExceptionHandler;
import org.example.ticket.common.response.ApiResponse;
import org.example.ticket.member.model.Member;
import org.example.ticket.reservation.admission.SeatAdmissionService;
import org.example.ticket.reservation.request.ReservationRequest;
import org.example.ticket.reservation.response.ReservationCreateResponse;
import org.example.ticket.reservation.service.ReservationService;
import org.example.ticket.security.util.MetamaskUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Controller, admission guard, error mapping을 함께 연결해 검증한다. */
class ReservationControllerAdmissionIntegrationTest {

    private static final String WALLET_ADDRESS = "0xadmission-test";

    @Test
    void returnsAdmission429BeforeCallingReservationServiceAndReleasesAfterResponse() throws Exception {
        ReservationService reservationService = mock(ReservationService.class);
        ReservationController controller = new ReservationController(
                reservationService,
                new SeatAdmissionService(1)
        );
        GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();
        CountDownLatch enteredService = new CountDownLatch(1);
        CountDownLatch releaseService = new CountDownLatch(1);
        AtomicInteger serviceCalls = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        when(reservationService.createReservation(eq(WALLET_ADDRESS), any(ReservationRequest.class)))
                .thenAnswer(invocation -> {
                    if (serviceCalls.getAndIncrement() == 0) {
                        enteredService.countDown();
                        if (!releaseService.await(1, TimeUnit.SECONDS)) {
                            throw new AssertionError("holder release를 기다리다 시간 초과했습니다.");
                        }
                    }
                    return successfulResponse();
                });

        Future<ResponseEntity<ApiResponse<ReservationCreateResponse>>> holder = executor.submit(
                () -> controller.registerReservation(user(), request(11L))
        );
        try {
            assertThat(enteredService.await(1, TimeUnit.SECONDS)).isTrue();

            BusinessException rejected = catchThrowableOfType(
                    () -> controller.registerReservation(user(), request(11L)),
                    BusinessException.class
            );
            assertThat(rejected.getErrorCode().code()).isEqualTo("SEAT_ADMISSION_REJECTED");

            ResponseEntity<ApiResponse<Void>> rejectionResponse = exceptionHandler.handleBusinessException(rejected);
            assertThat(rejectionResponse.getStatusCode().value()).isEqualTo(429);
            assertThat(rejectionResponse.getBody().getError().getCode()).isEqualTo("SEAT_ADMISSION_REJECTED");

            releaseService.countDown();
            assertThat(holder.get(1, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
            assertThat(controller.registerReservation(user(), request(11L)).getStatusCode().value()).isEqualTo(200);
            verify(reservationService, times(2))
                    .createReservation(eq(WALLET_ADDRESS), any(ReservationRequest.class));
        } finally {
            releaseService.countDown();
            executor.shutdownNow();
        }
    }

    private ReservationRequest request(Long seatId) {
        return new ReservationRequest(1L, List.of(seatId));
    }

    private MetamaskUserDetails user() {
        Member member = Member.builder()
                .walletAddress(WALLET_ADDRESS)
                .role("ROLE_USER")
                .build();
        return new MetamaskUserDetails(member);
    }

    private ReservationCreateResponse successfulResponse() {
        return ReservationCreateResponse.builder()
                .id(1L)
                .totalPrice(10000)
                .orderUid("admission-test")
                .responses(List.of())
                .build();
    }
}

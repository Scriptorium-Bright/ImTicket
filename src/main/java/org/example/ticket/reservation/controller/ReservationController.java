package org.example.ticket.reservation.controller;

import lombok.RequiredArgsConstructor;
import org.example.ticket.reservation.request.ReservationRequest;
import org.example.ticket.reservation.response.ReservationCreateResponse;
import org.example.ticket.reservation.service.ReservationPreReserveService;
import org.example.ticket.security.principal.MetamaskUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.example.ticket.common.response.ApiResponse;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reservation")
public class ReservationController {

    private final ReservationPreReserveService preReserveService;

    @PostMapping("/pre-reserve")
    /** 인증된 사용자의 좌석 선점 요청을 예약 서비스에 위임하고 결과를 HTTP 응답으로 감싼다. */
    public ResponseEntity<ApiResponse<ReservationCreateResponse>> registerReservation(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ReservationRequest reservationRequest) {
        ReservationCreateResponse response = preReserveService.preReserve(
                userDetails.getAddress(),
                idempotencyKey,
                reservationRequest
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}

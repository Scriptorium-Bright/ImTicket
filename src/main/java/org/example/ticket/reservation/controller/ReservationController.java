package org.example.ticket.reservation.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.reservation.admission.SeatAdmissionService;
import org.example.ticket.reservation.request.ReservationRequest;
import org.example.ticket.reservation.response.ReservationCreateResponse;
import org.example.ticket.reservation.service.ReservationService;
import org.example.ticket.security.util.MetamaskUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.example.ticket.common.response.ApiResponse;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/reservation")
public class ReservationController {

    private final ReservationService reservationService;
    private final SeatAdmissionService seatAdmissionService;

    @PostMapping("/pre-reserve")
    public ResponseEntity<ApiResponse<ReservationCreateResponse>> registerReservation(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @RequestBody ReservationRequest reservationRequest) {
        ReservationCreateResponse response = seatAdmissionService.execute(
                reservationRequest,
                () -> reservationService.createReservation(userDetails.getAddress(), reservationRequest)
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}

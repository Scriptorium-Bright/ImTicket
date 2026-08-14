package org.example.ticket.reservation.booking.controller;

import lombok.RequiredArgsConstructor;
import org.example.ticket.reservation.booking.dto.request.ReservationRequest;
import org.example.ticket.reservation.booking.dto.response.ReservationCreateResponse;
import org.example.ticket.reservation.booking.service.ReservationPreReserveService;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomAccessGuard;
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

    /**
     * 인증된 사용자의 동기 좌석 선점 요청을 접수한다.
     * 멱등 키와 요청을 서비스에 전달하고 생성 결과를 HTTP 응답으로 반환한다.
     */
    @PostMapping("/pre-reserve")
    public ResponseEntity<ApiResponse<ReservationCreateResponse>> registerReservation(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = WaitingRoomAccessGuard.PASS_HEADER, required = false) String entryPass,
            @RequestBody ReservationRequest reservationRequest) {
        ReservationCreateResponse response = preReserveService.preReserve(
                userDetails == null ? null : userDetails.getMemberId(),
                idempotencyKey,
                entryPass,
                reservationRequest
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}

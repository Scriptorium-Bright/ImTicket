package org.example.ticket.reservation.controller;

import com.siot.IamportRestClient.exception.IamportResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.model.Member;
import org.example.ticket.payment.request.VerifyPaymentRequest;
import org.example.ticket.reservation.request.ReservationRequest;
import org.example.ticket.reservation.response.ReservationCreateResponse;
import org.example.ticket.reservation.response.ReservationSuccessResponse;
import org.example.ticket.reservation.service.ReservationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/reservation")
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping("/pre-reserve")
    public ReservationCreateResponse registerReservation(@AuthenticationPrincipal Member member, @RequestBody ReservationRequest reservationRequest) {
        return reservationService.createReservation(member.getWalletAddress(), reservationRequest);
    }

    @PostMapping("/{reservationId}/confirm")
    public ReservationSuccessResponse completeReservation(@PathVariable Long reservationId, VerifyPaymentRequest request) throws IamportResponseException, IOException {
        return reservationService.confirmReservation(reservationId, request);
    }


}

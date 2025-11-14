package org.example.ticket.reservation.controller;

import com.siot.IamportRestClient.exception.IamportResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.model.Member;
import org.example.ticket.reservation.request.ReservationRequest;
import org.example.ticket.reservation.response.ReservationCreateResponse;
import org.example.ticket.reservation.response.ReservationSuccessResponse;
//import org.example.ticket.reservation.service.ReservationFacade;
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
//    private final ReservationFacade reservationFacade;

    @PostMapping("/pre-reserve")
    public ReservationCreateResponse registerReservation(@AuthenticationPrincipal Member member, @RequestBody ReservationRequest reservationRequest) {
        return reservationService.createReservation(member.getWalletAddress(), reservationRequest);
    }

/*    @PostMapping("/pre-reserve/optimistic")
    public ReservationCreateResponse registerReservationWithOptimistic(@AuthenticationPrincipal Member member, @RequestBody ReservationRequest reservationRequest) {
        return reservationService.createReservationWithOptimistic(member.getWalletAddress(), reservationRequest);
    }

    @PostMapping("/pre-reserve/distribution")
    public ReservationCreateResponse registerReservationWithDistribution(@AuthenticationPrincipal Member member, @RequestBody ReservationRequest reservationRequest) {
        return reservationFacade.createReservationWithLock(member.getWalletAddress(), reservationRequest);
    }*/
/*
    @PostMapping("/{reservationId}/confirm")
    public ReservationSuccessResponse completeReservation(@PathVariable Long reservationId) throws IamportResponseException, IOException {
        return reservationService.confirmReservation(reservationId);
    }*/


}

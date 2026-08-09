package org.example.ticket.performance.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.performance.request.SeatPriceRequest;
import org.example.ticket.performance.service.SeatPriceService;
import org.example.ticket.security.principal.MetamaskUserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.example.ticket.common.response.ApiResponse;

@RestController
@RequestMapping("/api/price")
@Slf4j
@RequiredArgsConstructor
public class SeatPriceController {

    private final SeatPriceService seatPriceService;

    @PostMapping("/enter/{performanceId}/prices")
    @PreAuthorize("hasAuthority('ROLE_ORGANIZER')")
    public ResponseEntity<ApiResponse<Void>> registerSeatPrice(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @PathVariable Long performanceId,
            @RequestBody List<SeatPriceRequest> seatPriceRequestList) {
        seatPriceService.setSeatPrice(seatPriceRequestList, performanceId, userDetails.getAddress());

        return ResponseEntity.ok(ApiResponse.success());
    }
}

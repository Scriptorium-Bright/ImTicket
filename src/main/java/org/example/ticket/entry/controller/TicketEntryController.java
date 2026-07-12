package org.example.ticket.entry.controller;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.example.ticket.entry.service.TicketEntryService;
import org.example.ticket.security.util.MetamaskUserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import org.example.ticket.common.response.ApiResponse;

@RestController
@RequestMapping("/api/entry")
@RequiredArgsConstructor
public class TicketEntryController {

    private final TicketEntryService ticketEntryService;

    @GetMapping("/token/{reservationId}")
    public ResponseEntity<ApiResponse<Map<String, String>>> getEntryToken(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @PathVariable Long reservationId) {
        String token = ticketEntryService.generateEntryToken(userDetails.getAddress(), reservationId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("token", token)));
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyEntry(HttpServletRequest httpServletRequest, @RequestBody Map<String, String> request) {
        String token = request.get("token");
        String gateName = request.getOrDefault("gateName", "Default Gate");
        ticketEntryService.verifyEntry(token, gateName);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Entry Confirmed", "valid", true)));
    }
}

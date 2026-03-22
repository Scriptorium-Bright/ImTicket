package org.example.ticket.entry.controller;

import lombok.RequiredArgsConstructor;
import org.example.ticket.entry.service.TicketEntryService;
import org.example.ticket.security.util.MetamaskUserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/entry")
@RequiredArgsConstructor
public class TicketEntryController {

    private final TicketEntryService ticketEntryService;

    @GetMapping("/token/{reservationId}")
    public ResponseEntity<?> getEntryToken(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @PathVariable Long reservationId) {
        try {
            String token = ticketEntryService.generateEntryToken(userDetails.getAddress(), reservationId);
            return ResponseEntity.ok(Map.of("token", token));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verifyEntry(@RequestBody Map<String, String> request) {
        try {
            String token = request.get("token");
            String gateName = request.getOrDefault("gateName", "Default Gate");
            ticketEntryService.verifyEntry(token, gateName);
            return ResponseEntity.ok(Map.of("message", "Entry Confirmed", "valid", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage(), "valid", false));
        }
    }
}

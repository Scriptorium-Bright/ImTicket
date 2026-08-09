package org.example.ticket.performance.controller;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.performance.request.PerformanceTimeRequest;
import org.example.ticket.performance.response.PerformanceTimeResponse;
import org.example.ticket.performance.service.PerformanceTimeService;
import org.example.ticket.security.principal.MetamaskUserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.example.ticket.common.response.ApiResponse;

@RestController
@RequestMapping("/api/time")
@Slf4j
@RequiredArgsConstructor
public class PerformanceTimeController {
    private final PerformanceTimeService performanceTimeService;

    @PostMapping("/enter/{performanceId}/times")
    @PreAuthorize("hasAuthority('ROLE_ORGANIZER')")
    public ResponseEntity<ApiResponse<List<PerformanceTimeResponse>>> registerPerformanceTime(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @PathVariable Long performanceId,
            @RequestBody List<PerformanceTimeRequest> requests) {
        List<PerformanceTimeResponse> performanceTimeResponses =
                performanceTimeService.allocatePerformanceTime(requests, performanceId, userDetails.getAddress());
        return ResponseEntity.ok(ApiResponse.success(performanceTimeResponses));
    }
}

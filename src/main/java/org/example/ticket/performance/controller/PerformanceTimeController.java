package org.example.ticket.performance.controller;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.performance.request.PerformanceTimeRequest;
import org.example.ticket.performance.response.PerformanceTimeResponse;
import org.example.ticket.performance.service.PerformanceTimeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/time")
@Slf4j
@RequiredArgsConstructor
public class PerformanceTimeController {
    private final PerformanceTimeService performanceTimeService;

    @PostMapping("/enter/{performanceId}/times")
    public ResponseEntity<?> registerPerformanceTime(@PathVariable Long performanceId, @RequestBody List<PerformanceTimeRequest> requests) {
        List<PerformanceTimeResponse> performanceTimeResponses = performanceTimeService.allocatePerformanceTime(requests, performanceId);
        return ResponseEntity.ok(performanceTimeResponses);
    }
}

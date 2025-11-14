package org.example.ticket.performance.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.performance.request.PerformanceDetailRequest;
import org.example.ticket.performance.request.PerformanceTimeRequest;
import org.example.ticket.performance.request.SeatPriceRequest;
import org.example.ticket.performance.response.PerformanceDetailsResponse;
import org.example.ticket.performance.response.PerformanceOverviewResponse;
import org.example.ticket.performance.response.PerformanceTimeResponse;
import org.example.ticket.performance.service.PerformanceService;
import org.example.ticket.performance.service.PerformanceTimeService;
import org.example.ticket.performance.service.SeatPriceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/performance")
public class PerformanceController {

    private final PerformanceService performanceService;

    @PostMapping("/enter")
    public ResponseEntity<Void> registerPerformance(@Validated @RequestPart("details") PerformanceDetailRequest detailsRequest,
                                                    @Validated @RequestPart("image") MultipartFile file) throws IOException {

        Long performanceId = performanceService.registerPerformance(detailsRequest, file);

        if (performanceId != null) {
            URI location = ServletUriComponentsBuilder
                    .fromCurrentContextPath().path("/api/performance/intro/{performanceId}")
                    .buildAndExpand(performanceId).toUri();
            return ResponseEntity.created(location).build();
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    @GetMapping("/intro/{performanceId}")
    public ResponseEntity<PerformanceDetailsResponse> retrieveEventDetails(@PathVariable Long performanceId) {
        PerformanceDetailsResponse detailsRequest = performanceService.viewPerformanceDetails(performanceId);
        return ResponseEntity.ok(detailsRequest);
    }

    @GetMapping("/intro")
    public ResponseEntity<?> retrieveEventOverview() {
        List<PerformanceOverviewResponse> overviewList = performanceService.viewPerformanceIntro();
        return ResponseEntity.ok(overviewList);
    }

}

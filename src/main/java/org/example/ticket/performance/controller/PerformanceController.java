package org.example.ticket.performance.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.performance.dto.request.PerformanceDetailRequest;
import org.example.ticket.performance.dto.request.PerformanceTimeRequest;
import org.example.ticket.performance.dto.request.SeatPriceRequest;
import org.example.ticket.performance.dto.response.PerformanceDetailsResponse;
import org.example.ticket.performance.dto.response.PerformanceOverviewResponse;
import org.example.ticket.performance.dto.response.PerformanceTimeResponse;
import org.example.ticket.performance.service.PerformanceService;
import org.example.ticket.performance.service.PerformanceTimeService;
import org.example.ticket.performance.service.SeatPriceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final SeatPriceService seatPriceService;
    private final PerformanceTimeService performanceTimeService;

    @PostMapping("/enter")
//    @PreAuthorize("hasrole('ORGANIZER')")
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

    @PostMapping("/enter/{performanceId}/prices")
//    @PreAuthorize("hasrole('ORGANIZER')")
    public ResponseEntity<?> registerSeatPrice(@PathVariable Long performanceId, @RequestBody List<SeatPriceRequest> seatPriceRequestList) {
        log.info("저장 완료");
        seatPriceService.setSeatPrice(seatPriceRequestList, performanceId);

        return ResponseEntity.ok().build();
    }

    @PostMapping("/enter/{performanceId}/times")
//    @PreAuthorize("hasrole('ORGANIZER')")
    public ResponseEntity<?> registerPerformanceTime(@PathVariable Long performanceId, @RequestBody List<PerformanceTimeRequest> requests) {
        List<PerformanceTimeResponse> performanceTimeResponses = performanceTimeService.allocatePerformanceTime(requests, performanceId);
        return ResponseEntity.ok(performanceTimeResponses);
    }

    @GetMapping("/intro/{performanceId}")
    public ResponseEntity<PerformanceDetailsResponse> retrieveEventDetails(@PathVariable Long performanceId) {
        PerformanceDetailsResponse detailsRequest = performanceService.viewPerformanceDetails(performanceId);
        return ResponseEntity.ok(detailsRequest);
    }

    @GetMapping("/intro")
    public ResponseEntity<?> retrieveEventOverview() {
        List<PerformanceOverviewResponse> overviewList = performanceService.viewPerformanceIntro();

        overviewList.forEach(e -> {
            log.info(String.valueOf(e.getEndDate()));
            log.info("image = {}", e.getImageUrl());
        });

        return ResponseEntity.ok(overviewList);
    }

}

package org.example.ticket.performance.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.performance.request.PerformanceDetailRequest;
import org.example.ticket.performance.response.PerformanceDetailsResponse;
import org.example.ticket.performance.response.PerformanceOverviewResponse;
import org.example.ticket.performance.service.PerformanceService;
import org.example.ticket.security.util.MetamaskUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.example.ticket.common.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    @PreAuthorize("hasAuthority('ROLE_ORGANIZER')")
    public ResponseEntity<ApiResponse<Void>> registerPerformance(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @Validated @RequestPart("details") PerformanceDetailRequest detailsRequest,
            @Validated @RequestPart("image") MultipartFile file) throws IOException {

        Long performanceId = performanceService.registerPerformance(userDetails.getAddress(), detailsRequest, file);

        if (performanceId != null) {
            URI location = ServletUriComponentsBuilder
                    .fromCurrentContextPath().path("/api/performance/intro/{performanceId}")
                    .buildAndExpand(performanceId).toUri();
            return ResponseEntity.created(location).body(ApiResponse.success());
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(org.example.ticket.common.response.ErrorResponse.of("INTERNAL_SERVER_ERROR", "공연 등록에 실패했습니다.")));
    }

    @GetMapping("/intro/{performanceId}")
    public ResponseEntity<ApiResponse<PerformanceDetailsResponse>> retrieveEventDetails(@PathVariable Long performanceId) {
        PerformanceDetailsResponse response = performanceService.viewPerformanceDetails(performanceId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/intro")
    public ResponseEntity<ApiResponse<List<PerformanceOverviewResponse>>> retrieveEventOverview() {
        List<PerformanceOverviewResponse> overviewList = performanceService.viewPerformanceIntro();
        return ResponseEntity.ok(ApiResponse.success(overviewList));
    }

}

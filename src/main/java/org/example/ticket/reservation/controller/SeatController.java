package org.example.ticket.reservation.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.reservation.response.SeatResponse;
import org.example.ticket.reservation.service.SeatService;
import org.example.ticket.security.principal.MetamaskUserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.http.ResponseEntity;
import org.example.ticket.common.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/seats")
public class SeatController {

    private final SeatService seatService;

    @PostMapping("{performanceTimeId}")
    @PreAuthorize("hasAuthority('ROLE_ORGANIZER')")
    /** 주최자의 공연 회차 좌석 생성 작업을 비동기로 시작한다. */
    public CompletableFuture<ResponseEntity<ApiResponse<Void>>> registerSeats(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @PathVariable Long performanceTimeId) {
        return seatService.preprocessSeatData(performanceTimeId, userDetails.getAddress())
                .thenApply(v -> ResponseEntity.ok(ApiResponse.success()));
    }

    @GetMapping("/{performanceTimeId}")
    /** 공연 회차의 좌석 목록을 조회해 좌석 배치도 응답으로 변환한다. */
    public ResponseEntity<ApiResponse<List<SeatResponse>>> viewSeatMap(@PathVariable Long performanceTimeId) {
        return ResponseEntity.ok(ApiResponse.success(seatService.viewSeatMap(performanceTimeId)));
    }

}

package org.example.ticket.reservation.booking.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.reservation.booking.dto.response.SeatResponse;
import org.example.ticket.reservation.booking.service.SeatService;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomAccessGuard;
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
    private final WaitingRoomAccessGuard accessGuard;

    /**
     * 주최자가 지정한 공연 회차의 좌석 생성 작업을 시작한다.
     * 비동기 서비스 완료 시점에 성공 응답을 반환한다.
     */
    @PostMapping("{performanceTimeId}")
    @PreAuthorize("hasAuthority('ROLE_ORGANIZER')")
    public CompletableFuture<ResponseEntity<ApiResponse<Void>>> registerSeats(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @PathVariable Long performanceTimeId) {
        return seatService.preprocessSeatData(performanceTimeId, userDetails.getAddress())
                .thenApply(v -> ResponseEntity.ok(ApiResponse.success()));
    }

    /**
     * 공연 회차의 좌석 배치도를 조회한다.
     * 서비스가 만든 좌석 응답 목록을 공통 HTTP 응답으로 감싼다.
     */
    @GetMapping("/{performanceTimeId}")
    public ResponseEntity<ApiResponse<List<SeatResponse>>> viewSeatMap(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @PathVariable Long performanceTimeId,
            @RequestHeader(value = WaitingRoomAccessGuard.PASS_HEADER, required = false) String entryPass) {
        accessGuard.authorize(
                performanceTimeId,
                userDetails == null ? null : userDetails.getMemberId(),
                entryPass
        );
        return ResponseEntity.ok(ApiResponse.success(seatService.viewSeatMap(performanceTimeId)));
    }

}

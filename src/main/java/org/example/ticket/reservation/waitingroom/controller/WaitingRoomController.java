package org.example.ticket.reservation.waitingroom.controller;

import lombok.RequiredArgsConstructor;
import org.example.ticket.common.response.ApiResponse;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomStatusResponse;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomService;
import org.example.ticket.security.principal.MetamaskUserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 회원의 Waiting Room join·status·cancel HTTP lifecycle을 제공한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reservation/waiting-room")
public class WaitingRoomController {

    private final WaitingRoomService waitingRoomService;

    /** 인증 회원을 회차별 Waiting Room에 등록하고 ticket 상태를 반환한다.
     * 동일 회원의 재호출은 service가 기존 ticket을 복구한다. */
    @PostMapping("/{performanceTimeId}/join")
    public ResponseEntity<ApiResponse<WaitingRoomStatusResponse>> join(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @PathVariable long performanceTimeId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                waitingRoomService.join(performanceTimeId, userDetails.getMemberId())
        ));
    }

    /** ticket owner에게 현재 순번 또는 admitted entry pass를 반환한다.
     * 다른 회원의 ticket은 service owner 검증에서 거절한다. */
    @GetMapping("/{performanceTimeId}/tickets/{ticketId}")
    public ResponseEntity<ApiResponse<WaitingRoomStatusResponse>> status(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @PathVariable long performanceTimeId,
            @PathVariable UUID ticketId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                waitingRoomService.status(performanceTimeId, userDetails.getMemberId(), ticketId)
        ));
    }

    /** ticket owner의 WAITING 또는 ADMITTED lifecycle을 취소한다.
     * 취소 뒤에는 active slot과 owner mapping이 정리된다. */
    @PostMapping("/{performanceTimeId}/tickets/{ticketId}/cancel")
    public ResponseEntity<ApiResponse<WaitingRoomStatusResponse>> cancel(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @PathVariable long performanceTimeId,
            @PathVariable UUID ticketId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                waitingRoomService.cancel(performanceTimeId, userDetails.getMemberId(), ticketId)
        ));
    }
}

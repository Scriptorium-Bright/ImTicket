package org.example.ticket.reservation.waitingroom.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.servlet.http.HttpServletResponse;
import org.example.ticket.reservation.waitingroom.sse.WaitingRoomSseNotificationService;
import org.example.ticket.security.principal.MetamaskUserDetails;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/** authenticated Waiting Room ticket owner에게 lifecycle SSE stream을 제공한다. */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ticket.application.role", havingValue = "waiting-room")
@RequestMapping("/api/reservation/waiting-room")
public class WaitingRoomSseController {

    private final WaitingRoomSseNotificationService notificationService;

    /** owner 검증 뒤 no-store SSE response를 열고 initial snapshot을 전달한다.
     * ticket lifecycle event는 이후 Redis Pub/Sub로 전달된다. */
    @GetMapping(value = "/{performanceTimeId}/tickets/{ticketId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @PathVariable long performanceTimeId,
            @PathVariable UUID ticketId,
            HttpServletResponse response
    ) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader(HttpHeaders.CONNECTION, "keep-alive");
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        return notificationService.open(
                performanceTimeId,
                userDetails.getMemberId(),
                ticketId
        );
    }
}

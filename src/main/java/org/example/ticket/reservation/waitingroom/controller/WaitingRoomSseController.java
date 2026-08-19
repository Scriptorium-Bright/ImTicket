package org.example.ticket.reservation.waitingroom.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.servlet.http.HttpServletResponse;
import org.example.ticket.reservation.waitingroom.sse.WaitingRoomSseNotificationService;
import org.example.ticket.reservation.waitingroom.sse.WaitingRoomJoinHandoffSseNotificationService;
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
@ConditionalOnProperty(name = "ticket.application.role", havingValue = "waiting-room")
@RequestMapping("/api/reservation/waiting-room")
public class WaitingRoomSseController {

    private final WaitingRoomSseNotificationService notificationService;
    private final WaitingRoomJoinHandoffSseNotificationService handoffSseService;

    /** 기존 ticket SSE 단위 테스트가 사용할 생성자다.
     * request SSE service 없이 기존 stream contract를 검증할 수 있게 한다. */
    public WaitingRoomSseController(WaitingRoomSseNotificationService notificationService) {
        this(notificationService, null);
    }

    /** Spring production context에서 ticket SSE와 request SSE를 연결한다.
     * 두 stream의 endpoint와 delivery 책임을 분리한다. */
    @Autowired
    public WaitingRoomSseController(
            WaitingRoomSseNotificationService notificationService,
            WaitingRoomJoinHandoffSseNotificationService handoffSseService
    ) {
        this.notificationService = notificationService;
        this.handoffSseService = handoffSseService;
    }

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

    /** 비동기 join request owner에게 ticket 생성 완료와 실패 상태를 전달한다.
     * 완료 event 뒤 frontend가 ticket SSE endpoint로 전환한다. */
    @GetMapping(value = "/{performanceTimeId}/join-requests/{requestId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter joinEvents(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @PathVariable long performanceTimeId,
            @PathVariable UUID requestId,
            HttpServletResponse response
    ) {
        if (handoffSseService == null) {
            throw new IllegalStateException("join handoff SSE is not configured");
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader(HttpHeaders.CONNECTION, "keep-alive");
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        return handoffSseService.open(performanceTimeId, userDetails.getMemberId(), requestId);
    }
}

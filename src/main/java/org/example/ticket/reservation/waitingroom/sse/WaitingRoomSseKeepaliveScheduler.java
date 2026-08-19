package org.example.ticket.reservation.waitingroom.sse;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** idle stream이 proxy timeout에 끊기지 않도록 keepalive event를 보낸다. */
@Component
@RequiredArgsConstructor
public class WaitingRoomSseKeepaliveScheduler {

    private final WaitingRoomSseEmitterRegistry emitterRegistry;

    /** local ticket SSE connection에 keepalive event를 전송한다.
     * reverse proxy idle timeout보다 짧은 주기를 유지한다. */
    @Scheduled(fixedDelayString = "${reservation.waiting-room.sse-keepalive-interval:15s}")
    public void publishKeepalive() {
        emitterRegistry.publishKeepalive();
    }
}

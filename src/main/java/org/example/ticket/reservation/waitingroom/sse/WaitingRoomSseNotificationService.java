package org.example.ticket.reservation.waitingroom.sse;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.waitingroom.constant.WaitingRoomErrorCode;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomStatusResponse;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/** owner 검증·initial snapshot·lifecycle delivery를 하나의 SSE lifecycle로 조율한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitingRoomSseNotificationService {

    private final WaitingRoomService waitingRoomService;
    private final WaitingRoomSseEmitterRegistry emitterRegistry;
    private final MeterRegistry meterRegistry;

    /** 인증된 ticket owner의 SSE connection을 열고 최신 snapshot을 첫 event로 전달한다.
     * 연결 등록과 initial snapshot 조회가 실패하면 emitter를 registry에서 제거한다. */
    public SseEmitter open(long performanceTimeId, long memberId, UUID ticketId) {
        waitingRoomService.status(performanceTimeId, memberId, ticketId);
        WaitingRoomSseConnection connection;
        try {
            connection = emitterRegistry.register(performanceTimeId, ticketId, memberId);
        } catch (WaitingRoomSseConnectionLimitException exception) {
            meterRegistry.counter("imticket.waiting-room.sse.connections", "result", "limit_reached").increment();
            throw new BusinessException(WaitingRoomErrorCode.WAITING_ROOM_STREAM_LIMIT_REACHED);
        }
        try {
            emitterRegistry.initialize(
                    connection,
                    () -> waitingRoomService.status(performanceTimeId, memberId, ticketId)
            );
            return connection.emitter();
        } catch (RuntimeException exception) {
            emitterRegistry.close(connection, "initial_snapshot_failure");
            meterRegistry.counter("imticket.waiting-room.sse.connections", "result", "failure").increment();
            throw exception;
        }
    }

    /** Pub/Sub event를 수신한 instance가 local connection에 최신 ticket response를 전달한다.
     * local emitter가 없으면 해당 application instance에서는 전달을 생략한다. */
    public void publish(WaitingRoomTicketLifecycleEvent event) {
        List<WaitingRoomSseConnection> connections = emitterRegistry.find(
                event.performanceTimeId(),
                event.ticketId()
        );
        if (connections.isEmpty()) {
            return;
        }
        try {
            WaitingRoomStatusResponse snapshot = waitingRoomService.status(
                    event.performanceTimeId(),
                    connections.getFirst().memberId(),
                    event.ticketId()
            );
            emitterRegistry.publish(
                    event.performanceTimeId(),
                    event.ticketId(),
                    snapshot,
                    event.occurredAt()
            );
        } catch (RuntimeException exception) {
            log.warn("Waiting Room SSE lifecycle delivery failed: ticketId={}", event.ticketId(), exception);
            connections.forEach(connection -> emitterRegistry.close(connection, "lifecycle_snapshot_failure"));
        }
    }
}

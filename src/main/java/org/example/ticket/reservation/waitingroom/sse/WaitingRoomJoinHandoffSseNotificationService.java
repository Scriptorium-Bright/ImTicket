package org.example.ticket.reservation.waitingroom.sse;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffFailureResponse;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffState;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomStatusResponse;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomJoinHandoffService;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomJoinHandoffStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** join request의 상태 SSE를 local emitter로 전달한다. */
@Slf4j
@Service
public class WaitingRoomJoinHandoffSseNotificationService {

    private final WaitingRoomProperties properties;
    private final WaitingRoomJoinHandoffService handoffService;
    @Qualifier("waitingRoomSseTaskExecutor")
    private final TaskExecutor sseTaskExecutor;
    private final MeterRegistry meterRegistry;
    private final Map<WaitingRoomJoinHandoffSseKey, SseEmitter> emitters = new ConcurrentHashMap<>();

    /** 비동기 join request SSE delivery에 필요한 구성 요소를 주입한다.
     * SSE executor는 명시적인 qualifier로 선택해 다른 TaskExecutor와 혼동하지 않는다. */
    public WaitingRoomJoinHandoffSseNotificationService(
            WaitingRoomProperties properties,
            WaitingRoomJoinHandoffService handoffService,
            @Qualifier("waitingRoomSseTaskExecutor") TaskExecutor sseTaskExecutor,
            MeterRegistry meterRegistry
    ) {
        this.properties = properties;
        this.handoffService = handoffService;
        this.sseTaskExecutor = sseTaskExecutor;
        this.meterRegistry = meterRegistry;
    }

    /** request owner를 검증하고 상태 SSE를 연다.
     * 연결 직후 authoritative request state를 첫 event로 보낸다. */
    public SseEmitter open(long performanceTimeId, long memberId, UUID requestId) {
        WaitingRoomJoinHandoffState state = handoffService.authorize(performanceTimeId, memberId, requestId);
        WaitingRoomJoinHandoffSseKey key = new WaitingRoomJoinHandoffSseKey(performanceTimeId, requestId, memberId);
        SseEmitter emitter = new SseEmitter(properties.getSseConnectionTimeout().toMillis());
        emitters.put(key, emitter);
        emitter.onCompletion(() -> remove(key, emitter));
        emitter.onTimeout(() -> remove(key, emitter));
        emitter.onError(error -> remove(key, emitter));
        try {
            deliver(key, emitter, state);
            meterRegistry.counter("imticket.waiting-room.join-handoff.sse-delivery", "result", "opened").increment();
            return emitter;
        } catch (RuntimeException exception) {
            remove(key, emitter);
            throw exception;
        }
    }

    /** Redis Pub/Sub event를 해당 request connection에 전달한다.
     * SSE write는 bounded executor에서 처리해 listener thread를 보호한다. */
    public void publish(WaitingRoomJoinHandoffLifecycleEvent event) {
        WaitingRoomJoinHandoffSseKey key = new WaitingRoomJoinHandoffSseKey(
                event.performanceTimeId(),
                event.requestId(),
                event.memberId()
        );
        SseEmitter emitter = emitters.get(key);
        if (emitter == null) {
            return;
        }
        try {
            sseTaskExecutor.execute(() -> {
                try {
                    WaitingRoomJoinHandoffState state = handoffService.authorize(
                            event.performanceTimeId(),
                            event.memberId(),
                            event.requestId()
                    );
                    deliver(key, emitter, state);
                } catch (RuntimeException exception) {
                    remove(key, emitter);
                    log.warn("Waiting Room join handoff SSE delivery failed: requestId={}", event.requestId(), exception);
                }
            });
        } catch (RuntimeException exception) {
            remove(key, emitter);
            meterRegistry.counter("imticket.waiting-room.join-handoff.sse-delivery", "result", "executor_rejected").increment();
        }
    }

    /** reverse proxy idle timeout보다 짧은 간격으로 request stream을 유지한다.
     * 연결이 유휴 상태에서도 client가 stream 종료를 오인하지 않게 한다. */
    @Scheduled(fixedDelayString = "${reservation.waiting-room.sse-keepalive-interval:15s}")
    public void publishKeepalive() {
        Instant now = Instant.now();
        emitters.forEach((key, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name("keepalive").data(Map.of("at", now.toString())));
            } catch (IOException | IllegalStateException exception) {
                remove(key, emitter);
            }
        });
    }

    /** request 상태에 맞는 SSE event를 전송한다.
     * completed 상태에서는 기존 ticket SSE로 전환할 수 있는 ticket snapshot을 함께 보낸다. */
    private void deliver(
            WaitingRoomJoinHandoffSseKey key,
            SseEmitter emitter,
            WaitingRoomJoinHandoffState state
    ) {
        try {
            if (state.status() == WaitingRoomJoinHandoffStatus.COMPLETED) {
                WaitingRoomStatusResponse ticket = handoffService.completedTicket(state);
                emitter.send(SseEmitter.event().name("ticket-created").data(ticket));
                remove(key, emitter);
                emitter.complete();
                meterRegistry.counter("imticket.waiting-room.join-handoff.sse-delivery", "result", "ticket_created").increment();
                return;
            }
            if (state.status() == WaitingRoomJoinHandoffStatus.FAILED) {
                emitter.send(SseEmitter.event().name("failed").data(new WaitingRoomJoinHandoffFailureResponse(
                        state.requestId(),
                        state.status(),
                        state.errorCode(),
                        state.retryable()
                )));
                remove(key, emitter);
                emitter.complete();
                meterRegistry.counter("imticket.waiting-room.join-handoff.sse-delivery", "result", "failed").increment();
                return;
            }
            String eventName = state.status() == WaitingRoomJoinHandoffStatus.PROCESSING ? "processing" : "queued";
            emitter.send(SseEmitter.event().name(eventName).data(handoffService.response(state)));
        } catch (IOException | IllegalStateException exception) {
            remove(key, emitter);
            meterRegistry.counter("imticket.waiting-room.join-handoff.sse-delivery", "result", "write_failure").increment();
            throw new IllegalStateException("join handoff SSE write failed", exception);
        }
    }

    /** 종료된 emitter를 local registry에서 제거한다.
     * 동일 request의 재연결이 새 emitter를 등록할 수 있게 한다. */
    private void remove(WaitingRoomJoinHandoffSseKey key, SseEmitter emitter) {
        emitters.remove(key, emitter);
    }

    private record WaitingRoomJoinHandoffSseKey(long performanceTimeId, UUID requestId, long memberId) {
    }
}

package org.example.ticket.reservation.waitingroom.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Redis Pub/Sub handoff event를 local request SSE connection에 전달한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaitingRoomJoinHandoffLifecycleSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final WaitingRoomJoinHandoffSseNotificationService notificationService;
    private final MeterRegistry meterRegistry;

    /** Redis payload를 handoff lifecycle event로 복원해 local SSE에 전달한다.
     * 잘못된 payload는 해당 연결에 전파하지 않고 실패 metric으로 기록한다. */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            WaitingRoomJoinHandoffLifecycleEvent event = objectMapper.readValue(
                    message.getBody(),
                    WaitingRoomJoinHandoffLifecycleEvent.class
            );
            notificationService.publish(event);
            meterRegistry.counter("imticket.waiting-room.join-handoff.sse-delivery", "result", "consumed").increment();
        } catch (IOException | RuntimeException exception) {
            meterRegistry.counter("imticket.waiting-room.join-handoff.sse-delivery", "result", "consume_failure").increment();
            log.warn(
                    "Waiting Room join handoff notification consume failed: payload={}",
                    new String(message.getBody(), StandardCharsets.UTF_8),
                    exception
            );
        }
    }
}

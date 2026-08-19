package org.example.ticket.reservation.waitingroom.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.io.IOException;

/** Redis Pub/Sub lifecycle event를 local SSE connection에 전달한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaitingRoomLifecycleSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final WaitingRoomSseNotificationService notificationService;
    private final MeterRegistry meterRegistry;

    /** payload를 역직렬화하고 해당 ticket을 보유한 local emitter로 전달한다.
     * 역직렬화 오류는 subscriber 전체 중단 없이 실패 metric으로 기록한다. */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            WaitingRoomTicketLifecycleEvent event = objectMapper.readValue(
                    message.getBody(),
                    WaitingRoomTicketLifecycleEvent.class
            );
            notificationService.publish(event);
            meterRegistry.counter("imticket.waiting-room.sse.pubsub", "operation", "consume", "result", "success").increment();
        } catch (IOException | RuntimeException exception) {
            meterRegistry.counter("imticket.waiting-room.sse.pubsub", "operation", "consume", "result", "failure").increment();
            log.warn(
                    "Waiting Room lifecycle notification consume failed: payload={}",
                    new String(message.getBody(), StandardCharsets.UTF_8),
                    exception
            );
        }
    }
}

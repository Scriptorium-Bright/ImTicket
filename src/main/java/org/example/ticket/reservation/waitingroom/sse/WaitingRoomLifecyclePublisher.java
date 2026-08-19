package org.example.ticket.reservation.waitingroom.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** lifecycle 전이를 Redis Pub/Sub로 publish해 connection 보유 instance에 전달한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaitingRoomLifecyclePublisher {

    public static final String CHANNEL = "waiting-room.lifecycle";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /** Redis state 전이 뒤 notification 전달을 시도한다.
     * 실패한 연결은 reconnect snapshot으로 복구한다. */
    @EventListener
    @Async("waitingRoomLifecycleTaskExecutor")
    public void publish(WaitingRoomTicketLifecycleEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(CHANNEL, payload);
            meterRegistry.counter("imticket.waiting-room.sse.pubsub", "operation", "publish", "result", "success").increment();
        } catch (JsonProcessingException | RuntimeException exception) {
            meterRegistry.counter("imticket.waiting-room.sse.pubsub", "operation", "publish", "result", "failure").increment();
            log.warn("Waiting Room lifecycle notification publish failed: ticketId={}", event.ticketId(), exception);
        }
    }
}

package org.example.ticket.reservation.waitingroom.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** join handoff 상태 변경을 Redis Pub/Sub으로 application instance에 전달한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaitingRoomJoinHandoffLifecyclePublisher {

    public static final String CHANNEL = "waiting-room.join-handoff";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /** application event를 Redis Pub/Sub payload로 변환해 publish한다.
     * publish 실패는 request state 처리와 분리해 로그·metric으로 기록한다. */
    @EventListener
    public void publish(WaitingRoomJoinHandoffLifecycleEvent event) {
        try {
            redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(event));
            meterRegistry.counter("imticket.waiting-room.join-handoff.sse-delivery", "result", "published").increment();
        } catch (JsonProcessingException | RuntimeException exception) {
            meterRegistry.counter("imticket.waiting-room.join-handoff.sse-delivery", "result", "publish_failure").increment();
            log.warn("Waiting Room join handoff notification publish failed: requestId={}", event.requestId(), exception);
        }
    }
}

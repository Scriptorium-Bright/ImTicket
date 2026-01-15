package org.example.ticket.venue.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.venue.dto.event.SeatCreationEvent;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeatCreationProducer {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    public static final String STREAM_KEY = "seat-creation-stream";

    public void publishEvent(SeatCreationEvent event) {
        log.info("Publishing SeatCreationEvent to Redis Stream: hallId={}", event.getHallId());

        try {
            String jsonContent = objectMapper.writeValueAsString(event);
            StringRecord record = StreamRecords.newRecord()
                    .ofStrings(Collections.singletonMap("payload", jsonContent))
                    .withStreamKey(STREAM_KEY);

            redisTemplate.opsForStream().add(record);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SeatCreationEvent", e);
            throw new RuntimeException(e);
        }
    }
}

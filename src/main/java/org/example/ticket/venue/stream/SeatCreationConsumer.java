package org.example.ticket.venue.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.util.tracing.TracingConstants;
import org.example.ticket.venue.dto.event.SeatCreationEvent;
import org.example.ticket.venue.service.VenueHallService;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeatCreationConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final VenueHallService venueHallService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        try {
            String jsonContent = message.getValue().get("payload");
            if (jsonContent == null) {
                MDC.put(TracingConstants.CORRELATION_ID_MDC_KEY, fallbackCorrelationId(message));
                log.warn("Payload is missing in stream message");
                return;
            }

            SeatCreationEvent event = objectMapper.readValue(jsonContent, SeatCreationEvent.class);
            MDC.put(TracingConstants.CORRELATION_ID_MDC_KEY, resolveCorrelationId(event, message));
            log.info("Received message from Redis Stream: id={}", message.getId());
            log.info("Processing seat creation for hallId: {}", event.getHallId());

            venueHallService.allocateSeatsInternal(event.getHallId(), event.getFloorRequestList());
            log.info("Successfully processed seat creation for hallId: {}", event.getHallId());

        } catch (IOException e) {
            ensureFallbackCorrelationId(message);
            log.error("Failed to deserialize stream message payload", e);
        } catch (Exception e) {
            ensureFallbackCorrelationId(message);
            log.error("Error processing seat creation", e);
        } finally {
            MDC.clear();
        }
    }

    private String resolveCorrelationId(SeatCreationEvent event, MapRecord<String, String, String> message) {
        if (event.getCorrelationId() == null || event.getCorrelationId().isBlank()) {
            return fallbackCorrelationId(message);
        }

        return event.getCorrelationId();
    }

    private void ensureFallbackCorrelationId(MapRecord<String, String, String> message) {
        if (MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY) == null) {
            MDC.put(TracingConstants.CORRELATION_ID_MDC_KEY, fallbackCorrelationId(message));
        }
    }

    private String fallbackCorrelationId(MapRecord<String, String, String> message) {
        return "stream:" + message.getId();
    }
}

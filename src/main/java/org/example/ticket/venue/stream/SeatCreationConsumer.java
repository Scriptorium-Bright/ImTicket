package org.example.ticket.venue.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.venue.dto.event.SeatCreationEvent;
import org.example.ticket.venue.service.VenueHallService;
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
        log.info("Received message from Redis Stream: id={}", message.getId());

        try {
            String jsonContent = message.getValue().get("payload");
            if (jsonContent == null) {
                log.warn("Payload is missing in stream message");
                return;
            }

            SeatCreationEvent event = objectMapper.readValue(jsonContent, SeatCreationEvent.class);
            log.info("Processing seat creation for hallId: {}", event.getHallId());

            venueHallService.allocateSeatsInternal(event.getHallId(), event.getFloorRequestList());
            log.info("Successfully processed seat creation for hallId: {}", event.getHallId());

        } catch (IOException e) {
            log.error("Failed to deserialize stream message payload", e);
        } catch (Exception e) {
            log.error("Error processing seat creation", e);
        }
    }
}

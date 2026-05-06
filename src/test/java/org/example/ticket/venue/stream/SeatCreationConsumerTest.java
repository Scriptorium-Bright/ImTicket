package org.example.ticket.venue.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ticket.util.tracing.TracingConstants;
import org.example.ticket.venue.dto.event.SeatCreationEvent;
import org.example.ticket.venue.dto.request.VenueHallFloorRequest;
import org.example.ticket.venue.service.VenueHallService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatCreationConsumerTest {

    @Mock
    private VenueHallService venueHallService;

    @Mock
    private ObjectMapper objectMapper;

    @Test
    void restoresEventCorrelationIdIntoMdcWhileProcessing() throws Exception {
        SeatCreationConsumer consumer = new SeatCreationConsumer(venueHallService, objectMapper);
        MapRecord<String, String, String> message = message("1700000000000-0", "payload-1");
        List<VenueHallFloorRequest> floorRequestList = List.of(
                VenueHallFloorRequest.builder().floor(1).section(List.of()).build()
        );
        SeatCreationEvent event = SeatCreationEvent.builder()
                .hallId(7L)
                .floorRequestList(floorRequestList)
                .correlationId("trace-123")
                .build();

        when(objectMapper.readValue("payload-1", SeatCreationEvent.class)).thenReturn(event);
        doAnswer(invocation -> {
            assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)).isEqualTo("trace-123");
            return null;
        }).when(venueHallService).allocateSeatsInternal(7L, floorRequestList);

        consumer.onMessage(message);

        verify(venueHallService).allocateSeatsInternal(7L, floorRequestList);
        assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)).isNull();
    }

    @Test
    void usesStreamRecordIdFallbackWhenEventCorrelationIdIsMissing() throws Exception {
        SeatCreationConsumer consumer = new SeatCreationConsumer(venueHallService, objectMapper);
        MapRecord<String, String, String> message = message("1700000000001-0", "payload-2");
        List<VenueHallFloorRequest> floorRequestList = List.of(
                VenueHallFloorRequest.builder().floor(2).section(List.of()).build()
        );
        SeatCreationEvent event = SeatCreationEvent.builder()
                .hallId(9L)
                .floorRequestList(floorRequestList)
                .correlationId(null)
                .build();

        when(objectMapper.readValue("payload-2", SeatCreationEvent.class)).thenReturn(event);
        doAnswer(invocation -> {
            assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)).isEqualTo("stream:1700000000001-0");
            return null;
        }).when(venueHallService).allocateSeatsInternal(9L, floorRequestList);

        consumer.onMessage(message);

        verify(venueHallService).allocateSeatsInternal(9L, floorRequestList);
        assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)).isNull();
    }

    private MapRecord<String, String, String> message(String recordId, String payload) {
        @SuppressWarnings("unchecked")
        MapRecord<String, String, String> message = mock(MapRecord.class);
        when(message.getId()).thenReturn(RecordId.of(recordId));
        when(message.getValue()).thenReturn(Map.of("payload", payload));
        return message;
    }
}

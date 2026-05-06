package org.example.ticket.venue.service;

import org.example.ticket.util.tracing.TracingConstants;
import org.example.ticket.venue.dto.event.SeatCreationEvent;
import org.example.ticket.venue.dto.request.VenueHallFloorRequest;
import org.example.ticket.venue.repository.VenueHallRepository;
import org.example.ticket.venue.stream.SeatCreationProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VenueHallServiceTest {

    @Mock
    private VenueHallRepository venueHallRepository;

    @Mock
    private VenueHallMapper venueHallMapper;

    @Mock
    private SeatCreationProducer seatCreationProducer;

    @InjectMocks
    private VenueHallService venueHallService;

    @Test
    void allocateEmptySeatTemplateSyncPublishesCorrelationIdFromMdc() {
        List<VenueHallFloorRequest> floorRequestList = List.of(
                VenueHallFloorRequest.builder()
                        .floor(1)
                        .section(List.of())
                        .build()
        );

        MDC.put(TracingConstants.CORRELATION_ID_MDC_KEY, "trace-123");

        try {
            venueHallService.allocateEmptySeatTemplateSync(7L, floorRequestList);
        } finally {
            MDC.clear();
        }

        ArgumentCaptor<SeatCreationEvent> captor = ArgumentCaptor.forClass(SeatCreationEvent.class);
        verify(seatCreationProducer).publishEvent(captor.capture());

        SeatCreationEvent event = captor.getValue();
        assertThat(event.getHallId()).isEqualTo(7L);
        assertThat(event.getFloorRequestList()).isEqualTo(floorRequestList);
        assertThat(event.getCorrelationId()).isEqualTo("trace-123");
    }

    @Test
    void allocateEmptySeatTemplateSyncPublishesNullCorrelationIdWhenMdcIsEmpty() {
        List<VenueHallFloorRequest> floorRequestList = List.of(
                VenueHallFloorRequest.builder()
                        .floor(2)
                        .section(List.of())
                        .build()
        );

        venueHallService.allocateEmptySeatTemplateSync(9L, floorRequestList);

        ArgumentCaptor<SeatCreationEvent> captor = ArgumentCaptor.forClass(SeatCreationEvent.class);
        verify(seatCreationProducer).publishEvent(captor.capture());

        SeatCreationEvent event = captor.getValue();
        assertThat(event.getHallId()).isEqualTo(9L);
        assertThat(event.getFloorRequestList()).isEqualTo(floorRequestList);
        assertThat(event.getCorrelationId()).isNull();
    }
}

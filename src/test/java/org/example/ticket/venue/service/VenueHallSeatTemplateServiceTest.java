package org.example.ticket.venue.service;

import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.venue.dto.request.VenueHallFloorRequest;
import org.example.ticket.venue.dto.request.VenueHallRowRequest;
import org.example.ticket.venue.dto.request.VenueHallSeatRequest;
import org.example.ticket.venue.dto.request.VenueHallSectionRequest;
import org.example.ticket.venue.model.VenueHall;
import org.example.ticket.venue.model.VenueHallSeatTemplate;
import org.example.ticket.venue.repository.VenueHallRepository;
import org.example.ticket.venue.repository.VenueHallSeatTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VenueHallSeatTemplateServiceTest {

    @Mock
    private VenueHallRepository venueHallRepository;

    @Mock
    private VenueHallSeatTemplateRepository seatTemplateRepository;

    @InjectMocks
    private VenueHallService venueHallService;

    @Test
    void allocateEmptySeatTemplateFlattensRangeRequestsIntoSeatTemplates() {
        VenueHall hall = VenueHall.builder()
                .id(1L)
                .name("main hall")
                .build();
        VenueHallFloorRequest request = VenueHallFloorRequest.builder()
                .floor(1)
                .section(List.of(VenueHallSectionRequest.builder()
                        .section("A")
                        .rows(List.of(VenueHallRowRequest.builder()
                                .row(1)
                                .seats(List.of(VenueHallSeatRequest.builder()
                                        .seatInfo(SeatInfo.VIP)
                                        .startSeatNumber(1)
                                        .endSeatNumber(3)
                                        .build()))
                                .build()))
                        .build()))
                .build();
        ArgumentCaptor<List<VenueHallSeatTemplate>> captor = ArgumentCaptor.forClass(List.class);

        when(venueHallRepository.findById(1L)).thenReturn(Optional.of(hall));

        venueHallService.allocateEmptySeatTemplate(1L, List.of(request));

        verify(seatTemplateRepository).deleteByVenueHallId(1L);
        verify(seatTemplateRepository).saveAll(captor.capture());

        List<VenueHallSeatTemplate> templates = captor.getValue();
        assertThat(templates).hasSize(3);
        assertThat(templates)
                .extracting(VenueHallSeatTemplate::getSeatNumber)
                .containsExactly(1, 2, 3);
        assertThat(templates)
                .allSatisfy(template -> {
                    assertThat(template.getVenueHall()).isSameAs(hall);
                    assertThat(template.getFloor()).isEqualTo(1);
                    assertThat(template.getSection()).isEqualTo("A");
                    assertThat(template.getRow()).isEqualTo(1);
                    assertThat(template.getSeatInfo()).isEqualTo(SeatInfo.VIP);
                    assertThat(template.isActive()).isTrue();
                });
    }
}

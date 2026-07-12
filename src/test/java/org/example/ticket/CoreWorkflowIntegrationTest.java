package org.example.ticket;

import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.repository.PerformanceRepository;
import org.example.ticket.venue.model.Venue;
import org.example.ticket.venue.model.VenueHall;
import org.example.ticket.venue.repository.VenueRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class CoreWorkflowIntegrationTest extends ApiIntegrationTestBase {

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private PerformanceRepository performanceRepository;

    @Test
    @DisplayName("API 통합 테스트: 공연장 및 공연 전체 조회 정상 동작 확인")
    void shouldReturnApisSuccessfully() throws Exception {
        // 1. Test Data Setup
        Venue venue = Venue.builder()
                .name("Test Venue")
                .address("Seoul, Korea")
                .phoneNumber("010-1234-5678")
                .build();

        VenueHall hall = VenueHall.builder()
                .name("Main Hall")
                .totalSeats(100)
                .build();
        venue.addHall(hall);
        venueRepository.save(venue);

        Performance performance = Performance.builder()
                .title("Test Performance")
                .description("This is a test performance")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(5))
                .build();
        performanceRepository.save(performance);

        // 2. Test Venue API
        mockMvc.perform(get("/api/venue/halls")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 3. Test Performance API
        mockMvc.perform(get("/api/performance/intro")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}

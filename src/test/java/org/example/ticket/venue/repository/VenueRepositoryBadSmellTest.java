package org.example.ticket.venue.repository;

import org.example.ticket.venue.model.Venue;
import org.example.ticket.venue.model.VenueHall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
public class VenueRepositoryBadSmellTest {

    @Autowired
    private VenueRepository venueRepository;

    @Test
    @DisplayName("공연장 조회: venue id 기준으로 hall id를 정상 조회한다")
    public void findVenueHallIdByVenueId() {
        Venue venue = Venue.builder()
                .name("main venue")
                .address("Seoul")
                .build();
        VenueHall hall = VenueHall.builder()
                .name("main hall")
                .totalSeats(100)
                .build();
        venue.addHall(hall);
        venueRepository.saveAndFlush(venue);

        List<Long> venueHallIds = venueRepository.findVenueHallIdsByVenueId(venue.getId());

        assertThat(venueHallIds).containsExactly(hall.getId());
    }
}

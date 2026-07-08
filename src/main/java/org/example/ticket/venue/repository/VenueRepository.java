package org.example.ticket.venue.repository;

import org.example.ticket.venue.model.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VenueRepository extends JpaRepository<Venue, Long> {

    @Query("SELECT vh.id FROM VenueHall vh WHERE vh.venue.id = :venueId")
    List<Long> findVenueHallIdsByVenueId(@Param("venueId") Long venueId);

}

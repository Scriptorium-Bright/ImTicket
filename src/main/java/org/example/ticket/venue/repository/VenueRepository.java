package org.example.ticket.venue.repository;

import org.example.ticket.venue.model.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface VenueRepository extends JpaRepository<Venue, Long> {
/*
    @Query("select new org.example.ticket.venue.dto.response.VenueResponse(v.name, v.address, v.phoneNumber, vh.name, vh.id) " +
            " from Venue v , VenueHall vh " +
            " where v.id = vh.id")*//*
    List<VenueResponse> findByVenueList();*/

    @Query("SELECT vh.id from Venue v JOIN FETCH VenueHall vh ON :venueId = vh.venue.id")
    Long findByVenueHallsId(Long venueId);

}

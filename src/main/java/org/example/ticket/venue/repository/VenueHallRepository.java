package org.example.ticket.venue.repository;

import org.example.ticket.venue.dto.response.VenueHallResponse;
import org.example.ticket.venue.model.VenueHall;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface VenueHallRepository extends JpaRepository<VenueHall, Long> {

    @Query("SELECT new org.example.ticket.venue.dto.response.VenueHallResponse(vh.id, vh.name, v.name) from VenueHall vh left join Venue v " +
            "ON vh.venue.id = v.id ")
    List<VenueHallResponse> findAllAsVenueHallResponse();
}

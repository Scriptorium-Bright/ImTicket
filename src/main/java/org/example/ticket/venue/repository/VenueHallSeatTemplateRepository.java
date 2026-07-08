package org.example.ticket.venue.repository;

import org.example.ticket.venue.model.VenueHallSeatTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VenueHallSeatTemplateRepository extends JpaRepository<VenueHallSeatTemplate, Long> {

    @Query("""
            select t
            from VenueHallSeatTemplate t
            where t.venueHall.id = :venueHallId
              and t.active = true
            order by t.floor, t.section, t.row, t.seatNumber
            """)
    List<VenueHallSeatTemplate> findActiveTemplatesByHallId(@Param("venueHallId") Long venueHallId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from VenueHallSeatTemplate t
            where t.venueHall.id = :venueHallId
            """)
    void deleteByVenueHallId(@Param("venueHallId") Long venueHallId);
}

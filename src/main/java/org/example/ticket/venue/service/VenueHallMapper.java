package org.example.ticket.venue.service;

import org.example.ticket.venue.dto.request.VenueHallFloorRequest;
import org.example.ticket.venue.dto.request.VenueHallRowRequest;
import org.example.ticket.venue.dto.request.VenueHallSeatRequest;
import org.example.ticket.venue.dto.request.VenueHallSectionRequest;
import org.example.ticket.venue.model.*;
import org.springframework.stereotype.Component;

@Component
public class VenueHallMapper {

    public VenueHallFloor toFloors(VenueHallFloorRequest request, VenueHall venueHall) {
        return VenueHallFloor.builder()
                .floor(request.getFloor())
                .venueHall(venueHall)
                .build();
    }

    public VenueHallSection toSections(VenueHallFloor floors, VenueHallSectionRequest sectionDTO) {
        return VenueHallSection.builder()
                .floor(floors)
                .section(sectionDTO.getSection())
                .build();
    }

    public VenueHallRow toRows(VenueHallSection sections, VenueHallRowRequest rowDTO) {
        return VenueHallRow.builder()
                .row(rowDTO.getRow())
                .sections(sections)
                .build();
    }

    public VenueHallSeat toSeats(VenueHallRow rows, VenueHallSeatRequest seatDTO, int seatNumber, Integer startNum, Integer endNum) {
        return VenueHallSeat.builder()
                .seatInfo(seatDTO.getSeatInfo())
                .startSeatNumber(startNum)
                .endSeatNumber(endNum)
                .seatNumber(seatNumber)
                .row(rows)
                .build();
    }

}

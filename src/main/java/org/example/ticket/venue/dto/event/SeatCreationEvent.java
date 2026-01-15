package org.example.ticket.venue.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.ticket.venue.dto.request.VenueHallFloorRequest;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatCreationEvent {
    private Long hallId;
    private List<VenueHallFloorRequest> floorRequestList;
}

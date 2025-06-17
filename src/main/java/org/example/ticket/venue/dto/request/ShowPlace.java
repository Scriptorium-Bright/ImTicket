package org.example.ticket.venue.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Builder
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class ShowPlace {

    private VenueRequest request;
    private List<VenueHallRequest> venueHallRequest;

}

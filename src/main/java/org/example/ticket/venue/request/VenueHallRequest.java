package org.example.ticket.venue.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class VenueHallRequest {

    private String name;
    private Integer totalSeats;
}

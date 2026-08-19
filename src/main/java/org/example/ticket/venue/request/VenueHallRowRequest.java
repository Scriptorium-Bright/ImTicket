package org.example.ticket.venue.request;

import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Builder
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class VenueHallRowRequest {

    @Min(1)
    private Integer row;

    private List<VenueHallSeatRequest> seats;
}

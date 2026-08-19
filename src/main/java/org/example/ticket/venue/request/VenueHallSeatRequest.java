package org.example.ticket.venue.request;

import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.util.constant.SeatInfo;

@Builder
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class VenueHallSeatRequest {

    private SeatInfo seatInfo;

    @Min(1)
    private Integer startSeatNumber;

    @Min(1)
    private Integer endSeatNumber;
}

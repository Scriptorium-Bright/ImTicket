package org.example.ticket.venue.dto.request;

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
public class VenueHallFloorRequest {

    @Min(1)
    private Integer floor;

    private List<VenueHallSectionRequest> section;

}

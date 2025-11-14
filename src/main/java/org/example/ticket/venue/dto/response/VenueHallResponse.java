package org.example.ticket.venue.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class VenueHallResponse {

    private Long hallId;
    private String hallName;
    private String venueName;

    public VenueHallResponse(Long hallId, String hallName, String venueName) {
        this.hallId = hallId;
        this.hallName = hallName;
        this.venueName = venueName;
    }
}

package org.example.ticket.venue.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.venue.model.Venue;

import java.util.ArrayList;
import java.util.List;

@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class VenueResponse {

    private String name;
    private String address;
    private String phoneNumber;
    private List<VenueHallResponse> venueHallResponseList;

    public static List<VenueResponse> from(List<Venue> venues) {
        List<VenueResponse> venueResponses = new ArrayList<>();

        venues.forEach(venue -> venueResponses.add(VenueResponse.builder()
                .name(venue.getName())
                .address(venue.getAddress())
                .phoneNumber(venue.getPhoneNumber())
                .venueHallResponseList(venue.getVenueHalls().stream()
                        .map(hall -> new VenueHallResponse(hall.getId(), hall.getName(), venue.getName()))
                        .toList())
                .build()));

        return venueResponses;
    }
}

package org.example.ticket.venue.dto.response;

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

    List<VenueHallResponse> venueHallResponseList;


    public static List<VenueResponse> from(List<Venue> venue) {

        List<VenueResponse> venueResponses = new ArrayList<>();

        venue.forEach(venueDTO -> {

            VenueResponse response = VenueResponse.builder()
                    .name(venueDTO.getName())
                    .address(venueDTO.getAddress())
                    .phoneNumber(venueDTO.getPhoneNumber())
                    .venueHallResponseList(
                            venueDTO.getVenueHalls().stream().map(e -> new VenueHallResponse(e.getId(), e.getName(), venueDTO.getName())
                            ).toList()
                    )
                    .build();
            venueResponses.add(response);
        });

        return venueResponses;
    }


}

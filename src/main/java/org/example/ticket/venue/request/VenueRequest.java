package org.example.ticket.venue.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class VenueRequest {

    private String name;
    private String address;
    private String phoneNumber;
}

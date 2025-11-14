package org.example.ticket.venue.service;

import lombok.RequiredArgsConstructor;
import org.example.ticket.venue.dto.request.VenueHallRequest;
import org.example.ticket.venue.dto.request.VenueRequest;
import org.example.ticket.venue.dto.response.VenueResponse;
import org.example.ticket.venue.model.Venue;
import org.example.ticket.venue.model.VenueHall;
import org.example.ticket.venue.repository.VenueRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VenueService {

    private final VenueRepository venueRepository;
    private final VenueHallService venueHallService;


    public Venue insertVenue(VenueRequest request, List<VenueHallRequest> venueHallRequest) {

        Venue venue =
                Venue.builder()
                        .name(request.getName())
                        .address(request.getAddress())
                        .phoneNumber(request.getPhoneNumber())
                        .build();

        venueHallRequest.forEach(hallRequest -> {
            VenueHall hall = VenueHall.builder()
                    .totalSeats(hallRequest.getTotalSeats())
                    .name(hallRequest.getName())
                    .build();
            venue.addHall(hall);
        });

        venueRepository.save(venue);

//        venueHallService.registerVenueHallInformation(venue, venueHallRequest);
        return venue;
    }

    public List<VenueResponse> viewVenueList() {

        List<Venue> venueList = venueRepository.findAll();
        return VenueResponse.from(venueList);
    }




}

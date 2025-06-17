package org.example.ticket.venue.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.venue.dto.request.*;
import org.example.ticket.venue.dto.request.ShowPlace;
import org.example.ticket.venue.dto.response.VenueHallResponse;
import org.example.ticket.venue.dto.response.VenueResponse;
import org.example.ticket.venue.service.VenueHallService;
import org.example.ticket.venue.service.VenueService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/venue")
public class VenueController {

    private final VenueService venueService;
    private final VenueHallService venueHallService;


    @PostMapping("/enter")
    public ResponseEntity<?> registerVenue(@RequestBody ShowPlace showPlace) {

        venueService.insertVenue(showPlace.getRequest(), showPlace.getVenueHallRequest());

        return ResponseEntity.ok().build();
    }


    @PostMapping("/enter/{hallId}/seats")
    public ResponseEntity<?> registerEmptySeats(@PathVariable Long hallId, @RequestBody List<VenueHallFloorRequest> requestList) {

        for (VenueHallFloorRequest venueHallFloorRequest : requestList) {
            Integer floor = venueHallFloorRequest.getFloor();
            System.out.println("floor = " + floor);
            List<VenueSectionRequest> section = venueHallFloorRequest.getSection();
            for (VenueSectionRequest venueSectionRequest : section) {
                System.out.println("venueSectionRequest.getSection() = " + venueSectionRequest.getSection());
                List<VenueHallRowRequest> rows = venueSectionRequest.getRows();
                for (VenueHallRowRequest row : rows) {
                    System.out.println("row.getRow() = " + row.getRow());
                    List<VenueHallSeatRequest> seats = row.getSeats();
                    for (VenueHallSeatRequest seat : seats) {
                        System.out.println("seat.getSeatInfo() = " + seat.getSeatInfo());
                        System.out.println("seat = " + seat.getEndSeatNumber());
                        System.out.println("seat.getStartSeatNumber() = " + seat.getStartSeatNumber());
                    }
                }
            }
        }

        venueHallService.allocateEmptySeatTemplate(hallId, requestList);

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/halls")
    public List<VenueResponse> viewVenueList() {
        List<VenueResponse> venueResponses = venueService.viewVenueList();

        for (VenueResponse venueRespons : venueResponses) {
            log.info(venueRespons.getAddress());
            log.info(venueRespons.getName());
            log.info(venueRespons.getVenueHallResponseList().stream().map(VenueHallResponse::getHallId).toString());
        }

        return venueResponses;
    }

}

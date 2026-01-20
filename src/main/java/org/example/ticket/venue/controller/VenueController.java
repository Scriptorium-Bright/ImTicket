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
    public ResponseEntity<Void> registerVenue(@RequestBody ShowPlace showPlace) {

        venueService.insertVenue(showPlace.getRequest(), showPlace.getVenueHallRequest());

        return ResponseEntity.ok().build();
    }

    @PostMapping("/enter/{hallId}/seats")
    public ResponseEntity<Void> registerEmptySeats(@PathVariable Long hallId,
            @RequestBody List<VenueHallFloorRequest> requestList,
            @RequestParam(defaultValue = "async") String type) {

        if ("stream".equalsIgnoreCase(type)) {
            venueHallService.allocateEmptySeatTemplateSync(hallId, requestList);
        } else {
            venueHallService.allocateEmptySeatTemplate(hallId, requestList);
        }

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/halls")
    public List<VenueHallResponse> viewVenueHallList() {
        return venueHallService.viewVenueHallList();
    }

}

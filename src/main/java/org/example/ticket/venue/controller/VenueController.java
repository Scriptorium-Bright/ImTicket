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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.example.ticket.common.response.ApiResponse;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/venue")
public class VenueController {

    private final VenueService venueService;
    private final VenueHallService venueHallService;

    @PostMapping("/enter")
    @PreAuthorize("hasAuthority('ROLE_ORGANIZER')")
    public ResponseEntity<ApiResponse<Void>> registerVenue(@RequestBody ShowPlace showPlace) {
        venueService.insertVenue(showPlace.getRequest(), showPlace.getVenueHallRequest());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/enter/{hallId}/seats")
    @PreAuthorize("hasAuthority('ROLE_ORGANIZER')")
    public ResponseEntity<ApiResponse<Void>> registerEmptySeats(@PathVariable Long hallId,
            @RequestBody List<VenueHallFloorRequest> requestList) {
        venueHallService.allocateEmptySeatTemplate(hallId, requestList);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
    }

    @GetMapping("/halls")
    public ResponseEntity<ApiResponse<List<VenueHallResponse>>> viewVenueHallList() {
        return ResponseEntity.ok(ApiResponse.success(venueHallService.viewVenueHallList()));
    }

}

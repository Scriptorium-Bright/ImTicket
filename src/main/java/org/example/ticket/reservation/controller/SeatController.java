package org.example.ticket.reservation.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.reservation.response.SeatResponse;
import org.example.ticket.reservation.service.SeatService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/seats")
public class SeatController {

    private final SeatService seatService;

    @PostMapping("{performanceTimeId}")
//    @PreAuthorize("hasrole('ORGANIZER')")
    public void registerSeats(@PathVariable Long performanceTimeId) {
        seatService.preprocessSeatData(performanceTimeId);
    }

/*    @PostMapping("{performanceTimeId}/noasync")
    public void registerSeatWithNoAsync(@PathVariable Long performanceTimeId) {
        seatService.preprocessSeatDataWithNoAsync(performanceTimeId);
    }*/


    @GetMapping("/{performanceTimeId}")
    public List<SeatResponse> viewEmptySeatList(@PathVariable Long performanceTimeId) {
        return seatService.viewEmptySeatList(performanceTimeId);
    }

}

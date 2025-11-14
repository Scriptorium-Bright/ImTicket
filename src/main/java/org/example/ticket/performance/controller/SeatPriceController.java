package org.example.ticket.performance.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.performance.request.SeatPriceRequest;
import org.example.ticket.performance.service.SeatPriceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/price")
@Slf4j
@RequiredArgsConstructor
public class SeatPriceController {

    private final SeatPriceService seatPriceService;

    @PostMapping("/enter/{performanceId}/prices")
    public ResponseEntity<?> registerSeatPrice(@PathVariable Long performanceId, @RequestBody List<SeatPriceRequest> seatPriceRequestList) {
        log.info("저장 완료");
        seatPriceService.setSeatPrice(seatPriceRequestList, performanceId);

        return ResponseEntity.ok().build();
    }
}

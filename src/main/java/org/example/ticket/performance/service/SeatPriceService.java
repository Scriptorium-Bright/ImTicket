package org.example.ticket.performance.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.example.ticket.performance.request.SeatPriceRequest;
import org.example.ticket.performance.response.SeatPriceResponse;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.SeatPrice;
import org.example.ticket.performance.repository.PerformanceRepository;
import org.example.ticket.performance.repository.SeatPriceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SeatPriceService {

    private final PerformanceRepository performanceRepository;
    private final SeatPriceRepository seatPriceRepository;

    @Transactional
    public void setSeatPrice(List<SeatPriceRequest> seatPriceRequestList,
                             Long performanceId) {

        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new EntityNotFoundException("공연 정보를 찾을 수 없습니다."));

        List<SeatPrice> seatPrices = seatPriceRequestList
                .stream()
                .map(
                        dto -> {
                            SeatPrice seatPrice = SeatPrice.builder()
                                    .performance(performance)
                                    .price(dto.getPrice())
                                    .seatInfo(dto.getSeatInfo())
                                    .build();
                            performance.addPrice(seatPrice);
                            return seatPrice;
                        }
                ).toList();

        seatPriceRepository.saveAll(seatPrices);

        SeatPriceResponse.from(seatPrices);
    }


}

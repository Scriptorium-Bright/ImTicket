package org.example.ticket.performance.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.example.ticket.performance.request.PerformanceTimeRequest;
import org.example.ticket.performance.response.PerformanceTimeResponse;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.performance.repository.PerformanceRepository;
import org.example.ticket.performance.repository.PerformanceTimeRepository;
import org.example.ticket.venue.model.VenueHall;
import org.example.ticket.venue.repository.VenueHallRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PerformanceTimeService {


    private final PerformanceTimeRepository performanceTimeRepository;
    private final PerformanceRepository performanceRepository;
    private final VenueHallRepository venueHallRepository;

    @Transactional
    public List<PerformanceTimeResponse> allocatePerformanceTime(List<PerformanceTimeRequest> performanceTimeRequests,
                                                                 Long performanceId) {


        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new EntityNotFoundException("공연 정보를 찾을 수 없습니다."));

        List<Long> venueHallIds = performanceTimeRequests.stream()
                .map(PerformanceTimeRequest::getVenueHallId)
                .distinct()
                .toList();

        List<VenueHall> venueHalls = venueHallRepository.findAllById(venueHallIds);
        Map<Long, VenueHall> venueHallMap = venueHalls.stream()
            .collect(Collectors.toMap(VenueHall::getId, hall -> hall));

        List<PerformanceTime> performanceTimes = performanceTimeRequests.stream().map(request -> {
            VenueHall venueHall = venueHallMap.get(request.getVenueHallId());
            if (venueHall == null) {
                throw new EntityNotFoundException("ID " + request.getVenueHallId() + "에 해당하는 공연 홀을 찾을 수 없습니다.");
            }

            return PerformanceTime.builder()
                    .performance(performance)
                    .venueHall(venueHall)
                    .showDate(request.getShowDate())
                    .showTime(request.getShowTime())
                    .build();

        }).toList();

        List<PerformanceTime> savedPerformanceTimes = performanceTimeRepository.saveAll(performanceTimes);
        return PerformanceTimeResponse.from(savedPerformanceTimes);
    }

}

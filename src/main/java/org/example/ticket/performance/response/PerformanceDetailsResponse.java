package org.example.ticket.performance.response;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.performance.model.Performance;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceDetailsResponse {

    private Integer ageLimit;
    private String imageUrl;
    private LocalDate startDate;
    private String description;
    private String title;

    List<PerformanceTimeResponse> performanceTimes;
    List<SeatPriceResponse> seatPrices;


    public static PerformanceDetailsResponse from(Performance performance) {

        return PerformanceDetailsResponse.builder()
                .title(performance.getTitle())
                .ageLimit(performance.getAgeLimit())
                .description(performance.getDescription())
                .imageUrl(performance.getImageUrl())
                .startDate(performance.getStartDate())

                .seatPrices(
                        performance.getSeatPrices().stream()
                                .map(SeatPriceResponse::new)
                                .collect(Collectors.toList())
                )
                .performanceTimes(
                        performance.getPerformanceTimes().stream()
                                .map(PerformanceTimeResponse::new)
                                .collect(Collectors.toList())
                )
                .build();

    }

}

package org.example.ticket.performance.request;

import lombok.Builder;
import lombok.Getter;
import org.example.ticket.util.constant.VenueType;
import org.example.ticket.performance.model.Performance;

import java.time.LocalDate;

@Getter
@Builder
public class PerformanceDetailRequest {

    private Integer age;
    private String description;
    private String title;
    private String imageUrl;
    private LocalDate startDate;
    private LocalDate endDate;
    private VenueType venueType;

    public static PerformanceDetailRequest from(Performance performance) {

        return PerformanceDetailRequest.builder()
                .imageUrl(performance.getImageUrl())
                .title(performance.getTitle())
                .age(performance.getAgeLimit())
                .description(performance.getDescription())
                .startDate(performance.getStartDate())
                .endDate(performance.getEndDate())
                .venueType(performance.getVenueType())
                .build();
    }


}

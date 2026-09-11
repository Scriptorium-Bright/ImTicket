package org.example.ticket.performance.request;

import lombok.Builder;
import lombok.Getter;
import org.example.ticket.util.constant.VenueType;

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

}

package org.example.ticket.performance.response;

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
//@AllArgsConstructor
public class PerformanceOverviewResponse {

    private Long id;
    private String title;
    private String imageUrl;
    private LocalDate startDate;
    private LocalDate endDate;

    public PerformanceOverviewResponse(Performance performance) {
        this.title = performance.getTitle();
        this.imageUrl = performance.getImageUrl();
        this.startDate = performance.getStartDate();
        this.endDate = performance.getEndDate();
    }

    public PerformanceOverviewResponse(Long id, String title, String imageUrl, LocalDate startDate, LocalDate endDate) {
        this.id = id;
        this.title = title;
        this.imageUrl = imageUrl;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public static List<PerformanceOverviewResponse> from(List<Performance> performanceList) {

        return performanceList
                .stream()
                .map(PerformanceOverviewResponse::new)
                .collect(Collectors.toList());

    }


}

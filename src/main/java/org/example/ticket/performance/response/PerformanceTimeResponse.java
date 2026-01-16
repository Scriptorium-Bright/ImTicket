package org.example.ticket.performance.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.performance.model.PerformanceTime;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import java.io.Serializable;

@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceTimeResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private LocalDate showDate;
    private LocalTime showTime;

    public PerformanceTimeResponse(PerformanceTime performanceTime) {
        this.id = performanceTime.getId();
        this.showDate = performanceTime.getShowDate();
        this.showTime = performanceTime.getShowTime();
    }

    public static List<PerformanceTimeResponse> from(List<PerformanceTime> performanceTimes) {
        return performanceTimes.stream()
                .map(PerformanceTimeResponse::new)
                .toList();
    }

}

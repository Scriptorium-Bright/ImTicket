package org.example.ticket.performance.request;

import jakarta.persistence.Column;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Builder
public class PerformanceTimeRequest {

    @NotNull
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate showDate;

    @NotNull
    @DateTimeFormat(pattern = "HH:mm")
    private LocalTime showTime;

    private Long venueHallId;


}

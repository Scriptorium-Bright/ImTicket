package org.example.ticket.reservation.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ReservationRequest {

    private Long performanceTimeId;

    @NotEmpty
    @JsonProperty("seatIds")
    private List<Long> seatIds;

    /** 공연 회차와 예약 대상 좌석 ID를 담은 요청 객체를 생성한다. */
    public ReservationRequest(Long performanceTimeId, List<Long> seatIds) {
        this.performanceTimeId = performanceTimeId;
        this.seatIds = seatIds;
    }
}

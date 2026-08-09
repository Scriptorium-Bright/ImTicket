package org.example.ticket.reservation.booking.dto.request;

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

    /**
     * 공연 회차와 예약 대상 좌석 ID를 담은 요청 객체를 생성한다.
     * 동기 API와 내부 테스트가 동일한 예약 입력 형식을 사용하게 한다.
     */
    public ReservationRequest(Long performanceTimeId, List<Long> seatIds) {
        this.performanceTimeId = performanceTimeId;
        this.seatIds = seatIds;
    }
}

package org.example.ticket.reservation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.util.constant.SeatInfo;

/** 외부 API 요청이 아니라 Seat entity 생성을 위해 사용하는 내부 데이터다. */
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SeatCreationData {

    private Long seatId;
    private Integer seatFloor;
    private String seatSection;
    private Integer seatRow;
    private Integer seatNumber;
    private SeatInfo seatType;
    private Integer price;
    private Boolean isReservation;
}

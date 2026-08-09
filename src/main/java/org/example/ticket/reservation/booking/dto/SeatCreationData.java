package org.example.ticket.reservation.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.util.constant.SeatInfo;

/** Seat entity 생성에 필요한 내부 데이터를 전달한다. */
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

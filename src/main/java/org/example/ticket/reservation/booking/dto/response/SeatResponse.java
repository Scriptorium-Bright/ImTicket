package org.example.ticket.reservation.booking.dto.response;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatResponse {

    private Long id;

    private Integer seatFloor;
    private String seatSection;
    private Integer seatRow;
    private Integer seatNumber;
    private SeatInfo seatType;
    private Integer price;
    private Boolean isReservation;
    private SeatStatus seatStatus;

    /**
     * Seat entity에서 API 응답에 필요한 배치와 상태 정보를 복사한다.
     * 예약 응답이 영속 entity를 직접 노출하지 않게 한다.
     */
    public SeatResponse(Seat seat) {
        this.seatFloor = seat.getSeatFloor();
        this.seatNumber = seat.getSeatNumber();
        this.seatRow = seat.getSeatRow();
        this.seatSection = seat.getSeatSection();
        this.seatType = seat.getSeatType();
        this.seatStatus = seat.getSeatStatus();
    }



}

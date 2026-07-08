package org.example.ticket.reservation.response;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.reservation.model.Seat;
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

    public SeatResponse(Seat seat) {
        this.seatFloor = seat.getSeatFloor();
        this.seatNumber = seat.getSeatNumber();
        this.seatRow = seat.getSeatRow();
        this.seatSection = seat.getSeatSection();
        this.seatType = seat.getSeatType();
        this.seatStatus = seat.getSeatStatus();
    }



}

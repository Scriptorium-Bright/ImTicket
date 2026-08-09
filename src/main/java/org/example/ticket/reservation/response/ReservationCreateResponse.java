package org.example.ticket.reservation.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.reservation.model.Reservation;

import java.time.LocalDateTime;
import java.util.List;

@Builder
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class ReservationCreateResponse {

    private Long id;
    private Integer totalPrice;
    private String orderUid;
    private LocalDateTime expiredTime;

    private List<SeatResponse> responses;

    /** 예약 entity와 예약 좌석을 외부 pre-reserve 응답 형식으로 변환한다. */
    public static ReservationCreateResponse from(Reservation reservation) {

        return ReservationCreateResponse.builder()
                .id(reservation.getId())
                .totalPrice(reservation.getTotalPrice())
                .orderUid(reservation.getReservationCode())
                .expiredTime(reservation.getExpiredTime())
                .responses(
                        reservation.getReservedSeats().stream().map(
                                        reservedSeat -> new SeatResponse(reservedSeat.getSeat())
                                )
                                .toList()
                )
                .build();

    }


}

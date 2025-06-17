package org.example.ticket.reservation.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.reservation.model.Reservation;

import java.util.List;

@Builder
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class ReservationSuccessResponse {

    private String nickname;
    private Integer totalPrice;
    private String title;
    private String organizerWallet;
    private String memberWallet;

    private List<SeatResponse> responses;

    public static ReservationSuccessResponse from(Reservation reservation, Performance performance, String byWalletAddressByOrganizer) {

        return ReservationSuccessResponse.builder()
                .nickname(reservation.getMember().getNickname())
                .totalPrice(reservation.getTotalPrice())
                .title(performance.getTitle())
                .memberWallet(reservation.getMember().getWalletAddress())
                .organizerWallet(byWalletAddressByOrganizer)
                .responses(
                        reservation.getReservedSeats().stream().map(
                                        reservedSeat -> new SeatResponse(reservedSeat.getSeat())
                                )
                                .toList()
                )
                .build();

    }

}

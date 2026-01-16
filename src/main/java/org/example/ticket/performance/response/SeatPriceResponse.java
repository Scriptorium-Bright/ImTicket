package org.example.ticket.performance.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.performance.model.SeatPrice;
import org.example.ticket.util.constant.SeatInfo;

import java.util.List;

import java.io.Serializable;

@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SeatPriceResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer price;
    private SeatInfo seatInfo;

    public SeatPriceResponse(SeatPrice seatPrice) {
        this.price = seatPrice.getPrice();
        this.seatInfo = seatPrice.getSeatInfo();
    }

    public static List<SeatPriceResponse> from(List<SeatPrice> seatPrices) {
        return seatPrices.stream()
                .map(SeatPriceResponse::new)
                .toList();
    }

}

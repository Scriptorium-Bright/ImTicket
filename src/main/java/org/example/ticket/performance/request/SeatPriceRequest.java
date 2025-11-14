package org.example.ticket.performance.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import org.example.ticket.util.constant.SeatInfo;

@Getter
@Builder
public class SeatPriceRequest {


    private SeatInfo seatInfo;

    @Size(min = 35000, max = 250000)
    private Integer price;


}

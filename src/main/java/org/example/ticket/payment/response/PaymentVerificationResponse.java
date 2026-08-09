package org.example.ticket.payment.response;

import lombok.Builder;
import lombok.Getter;
import org.example.ticket.payment.constant.PaymentOrderStatus;
import org.example.ticket.payment.model.PaymentOrder;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.util.constant.ReservationStatus;

@Getter
@Builder
public class PaymentVerificationResponse {

    private Long paymentOrderId;
    private Long reservationId;
    private PaymentOrderStatus paymentStatus;
    private ReservationStatus reservationStatus;
    private Integer amount;
    private String currency;
    private String providerTransactionId;

    public static PaymentVerificationResponse of(PaymentOrder order, Reservation reservation,
                                                  String providerTransactionId) {
        return PaymentVerificationResponse.builder()
                .paymentOrderId(order.getId())
                .reservationId(reservation.getId())
                .paymentStatus(order.getStatus())
                .reservationStatus(reservation.getReservationStatus())
                .amount(order.getAmount())
                .currency(order.getCurrency())
                .providerTransactionId(providerTransactionId)
                .build();
    }
}

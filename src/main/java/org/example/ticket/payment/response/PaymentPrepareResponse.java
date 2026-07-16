package org.example.ticket.payment.response;

import lombok.Builder;
import lombok.Getter;
import org.example.ticket.payment.model.PaymentOrder;

import java.time.LocalDateTime;

@Getter
@Builder
public class PaymentPrepareResponse {

    private Long paymentOrderId;
    private Long reservationId;
    private String merchantOrderId;
    private String provider;
    private String fakeProviderTransactionId;
    private Integer amount;
    private String currency;
    private LocalDateTime paymentDeadline;

    public static PaymentPrepareResponse from(PaymentOrder order, String provider, String fakeProviderTransactionId) {
        return PaymentPrepareResponse.builder()
                .paymentOrderId(order.getId())
                .reservationId(order.getReservation().getId())
                .merchantOrderId(order.getMerchantOrderId())
                .provider(provider)
                .fakeProviderTransactionId(fakeProviderTransactionId)
                .amount(order.getAmount())
                .currency(order.getCurrency())
                .paymentDeadline(order.getReservation().getExpiredTime())
                .build();
    }
}

package org.example.ticket.lifecycle.event;

/** 예약·결제 Lifecycle을 재구성하는 최소 업무 사건 종류다. */
public enum LifecycleEventType {
    RESERVATION_CREATED("ReservationCreated"),
    PAYMENT_PREPARED("PaymentPrepared"),
    PAYMENT_APPROVED("PaymentApproved"),
    RESERVATION_COMPLETED("ReservationCompleted"),
    RESERVATION_EXPIRED("ReservationExpired"),
    PAYMENT_REFUND_PENDING("PaymentRefundPending");

    private final String wireValue;

    LifecycleEventType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}

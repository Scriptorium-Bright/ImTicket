package org.example.ticket.lifecycle.event;

/** 사건 payload에서 상태 변경의 대상을 구분한다. */
public enum LifecycleEntityType {
    RESERVATION,
    SEAT,
    PAYMENT_ORDER,
    PAYMENT_ATTEMPT
}

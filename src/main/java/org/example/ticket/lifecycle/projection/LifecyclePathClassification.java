package org.example.ticket.lifecycle.projection;

/** 사건 이력으로 판정한 예약·결제 실행 경로다. */
public enum LifecyclePathClassification {
    IN_PROGRESS,
    NORMAL_COMPLETED,
    EXPIRED_WITHOUT_PAYMENT,
    EXPIRATION_FIRST,
    PAYMENT_HANDLER_EXPIRED
}

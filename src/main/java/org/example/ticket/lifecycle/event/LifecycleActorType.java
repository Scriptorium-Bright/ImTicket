package org.example.ticket.lifecycle.event;

/** Lifecycle 상태 변경을 결정한 현재 애플리케이션 주체다. */
public enum LifecycleActorType {
    RESERVATION_SERVICE,
    PAYMENT_PREPARATION,
    PAYMENT_VERIFICATION,
    EXPIRATION_SCHEDULER
}

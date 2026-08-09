package org.example.ticket.reservation.booking.concurrency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 좌석 예매 진입점에 ReservationLockAspect의 동시성 제어를 적용한다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReservationLock {

    /**
     * CONFIGURED면 reservation.lock-strategy 설정값을 사용한다.
     * 구체적인 전략을 지정하면 해당 예매 진입점에서만 설정값을 덮어쓴다.
     */
    ReservationLockStrategy strategy() default ReservationLockStrategy.CONFIGURED;
}

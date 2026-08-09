package org.example.ticket.reservation.lock;

/** lock 전략 적용 전후에 실행할, checked exception을 전달하는 예약 작업이다. */
@FunctionalInterface
public interface ReservationLockOperation<T> {

    T run() throws Throwable;
}

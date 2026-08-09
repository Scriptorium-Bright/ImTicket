package org.example.ticket.reservation.booking.util.lock;

/** lock 전략 적용 전후에 실행할, checked exception을 전달하는 예약 작업이다. */
@FunctionalInterface
public interface ReservationLockOperation<T> {

    /**
     * 선택된 lock 범위 안에서 예약 작업을 실행한다.
     * 원래 작업의 반환값과 checked 예외를 호출자에게 그대로 전달한다.
     */
    T run() throws Throwable;
}

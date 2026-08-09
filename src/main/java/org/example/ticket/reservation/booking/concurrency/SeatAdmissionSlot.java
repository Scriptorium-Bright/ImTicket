package org.example.ticket.reservation.booking.concurrency;

import java.util.concurrent.Semaphore;

/** 한 좌석의 JVM-local 동시 진입 permit을 캡슐화한다. */
final class SeatAdmissionSlot {

    private final Semaphore permits;

    SeatAdmissionSlot(int permitsPerSeat) {
        this.permits = new Semaphore(permitsPerSeat, true);
    }

    boolean tryAcquire() {
        return permits.tryAcquire();
    }

    void release() {
        permits.release();
    }

    boolean isFullyReleased(int permitsPerSeat) {
        return permits.availablePermits() == permitsPerSeat;
    }
}

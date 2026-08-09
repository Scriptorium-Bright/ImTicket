package org.example.ticket.reservation.booking.util.admission;

import java.util.concurrent.Semaphore;

/** 한 좌석의 JVM-local 동시 진입 permit을 캡슐화한다. */
final class SeatAdmissionSlot {

    private final Semaphore permits;

    /**
     * 좌석 한 개에 허용할 동시 진입 수로 공정 semaphore를 만든다.
     * 먼저 대기한 요청이 먼저 permit을 얻도록 설정한다.
     */
    SeatAdmissionSlot(int permitsPerSeat) {
        this.permits = new Semaphore(permitsPerSeat, true);
    }

    /**
     * 대기 없이 좌석 permit 한 개를 획득한다.
     * 현재 수용량이 없으면 false를 반환해 요청을 빠르게 거절한다.
     */
    boolean tryAcquire() {
        return permits.tryAcquire();
    }

    /**
     * 처리 종료된 좌석 permit 한 개를 반환한다.
     * 다음 대기 요청이 해당 좌석에 진입할 수 있게 한다.
     */
    void release() {
        permits.release();
    }

    /**
     * 초기 수용량만큼 permit이 모두 돌아왔는지 확인한다.
     * 사용하지 않는 좌석 slot을 map에서 제거할 때 사용한다.
     */
    boolean isFullyReleased(int permitsPerSeat) {
        return permits.availablePermits() == permitsPerSeat;
    }
}

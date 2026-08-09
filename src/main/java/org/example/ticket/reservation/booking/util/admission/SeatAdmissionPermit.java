package org.example.ticket.reservation.booking.util.admission;

/** 한 요청이 특정 좌석에서 보유한 admission permit이다. */
final class SeatAdmissionPermit {

    private final Long seatId;
    private final SeatAdmissionSlot slot;

    /**
     * 좌석 ID와 해당 좌석의 admission slot을 하나의 보유 값으로 묶는다.
     * 해제 단계가 정확한 slot에 permit을 반환할 수 있게 한다.
     */
    SeatAdmissionPermit(Long seatId, SeatAdmissionSlot slot) {
        this.seatId = seatId;
        this.slot = slot;
    }

    /**
     * 이 permit이 보호하는 좌석 ID를 반환한다.
     * 해제 순서와 slot 정리 기준을 계산할 때 사용한다.
     */
    Long seatId() {
        return seatId;
    }

    /**
     * permit을 획득한 좌석별 slot을 반환한다.
     * admission 종료 시 동일한 semaphore에 permit을 돌려준다.
     */
    SeatAdmissionSlot slot() {
        return slot;
    }
}

package org.example.ticket.reservation.admission;

/** 한 요청이 특정 좌석에서 보유한 admission permit이다. */
final class SeatAdmissionPermit {

    private final Long seatId;
    private final SeatAdmissionSlot slot;

    SeatAdmissionPermit(Long seatId, SeatAdmissionSlot slot) {
        this.seatId = seatId;
        this.slot = slot;
    }

    Long seatId() {
        return seatId;
    }

    SeatAdmissionSlot slot() {
        return slot;
    }
}

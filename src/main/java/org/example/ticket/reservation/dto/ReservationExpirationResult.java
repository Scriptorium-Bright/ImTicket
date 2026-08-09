package org.example.ticket.reservation.dto;

/** 한 번의 예약 만료 정리에서 변경한 예약과 좌석의 개수다. */
public record ReservationExpirationResult(int reservationCount, int seatCount) {

    public static ReservationExpirationResult empty() {
        return new ReservationExpirationResult(0, 0);
    }
}

package org.example.ticket.reservation.booking.dto;

/** 한 번의 예약 만료 정리에서 변경한 예약과 좌석의 개수다. */
public record ReservationExpirationResult(int reservationCount, int seatCount) {

    /**
     * 변경 대상이 없을 때 사용할 빈 만료 결과를 만든다.
     * 호출자는 null 검사 없이 예약 수와 좌석 수를 합산할 수 있다.
     */
    public static ReservationExpirationResult empty() {
        return new ReservationExpirationResult(0, 0);
    }
}

package org.example.ticket.reservation.booking.dto;

/** 만료 배치에서 예약별 좌석 사건을 구성하기 위한 연결 정보다. */
public record ReservationSeatReference(Long reservationId, Long seatId) {
}

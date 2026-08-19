package org.example.ticket.reservation.booking.cache;

/** 좌석 상태 변경 transaction이 commit된 뒤 snapshot을 삭제하기 위한 event다. */
public record SeatMapInvalidationEvent(long performanceTimeId) {
}

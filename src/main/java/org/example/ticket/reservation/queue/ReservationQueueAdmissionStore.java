package org.example.ticket.reservation.queue;

/** 예약 요청을 bounded Redis queue에 접수한다. */
public interface ReservationQueueAdmissionStore {

    ReservationQueueAdmissionResult admit(ReservationQueueAdmissionCommand command);
}

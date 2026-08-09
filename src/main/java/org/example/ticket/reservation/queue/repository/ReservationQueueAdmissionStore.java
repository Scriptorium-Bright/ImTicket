package org.example.ticket.reservation.queue.repository;

import org.example.ticket.reservation.queue.dto.ReservationQueueAdmissionCommand;
import org.example.ticket.reservation.queue.dto.ReservationQueueAdmissionResult;

/** 예약 요청을 bounded Redis queue에 접수한다. */
public interface ReservationQueueAdmissionStore {

    /**
     * 정규화된 예약 요청을 bounded Queue에 원자적으로 접수한다.
     * 신규 접수, 기존 ticket과 거절 결과 중 하나를 반환한다.
     */
    ReservationQueueAdmissionResult admit(ReservationQueueAdmissionCommand command);
}

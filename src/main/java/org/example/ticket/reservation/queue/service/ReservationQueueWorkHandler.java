package org.example.ticket.reservation.queue.service;

import org.example.ticket.reservation.queue.dto.ReservationQueueStreamMessage;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;
import org.example.ticket.reservation.queue.exception.ReservationQueuePayloadException;

/** Bounded poller가 넘긴 정상 또는 거절된 Stream 작업을 처리하는 계약이다. */
public interface ReservationQueueWorkHandler {

    /**
     * Redis PROCESSING claim을 얻은 검증된 예약 작업을 실행한다.
     * 구현체는 DB 결과와 Redis terminal, ACK의 순서를 책임진다.
     */
    void handle(ReservationQueueWorkItem item, String workerId);

    /**
     * DB 호출 전에 거절된 payload를 공개 final 상태로 수렴시킨다.
     * ticket identity도 손상된 entry는 안전하게 ACK해 반복 전달을 막는다.
     */
    void reject(
            ReservationQueueStreamMessage message,
            String workerId,
            ReservationQueuePayloadException exception
    );
}

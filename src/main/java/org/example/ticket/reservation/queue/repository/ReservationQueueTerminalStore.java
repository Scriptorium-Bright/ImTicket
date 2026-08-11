package org.example.ticket.reservation.queue.repository;

import org.example.ticket.reservation.queue.dto.ReservationQueueStreamMessage;
import org.example.ticket.reservation.queue.dto.ReservationQueueSuccessResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueTerminalResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;

import java.time.Instant;

/** DB 처리 결과를 Redis ticket terminal 상태로 반영하는 저장소 계약이다. */
public interface ReservationQueueTerminalStore {

    /**
     * worker owner가 일치하는 PROCESSING ticket에 성공 snapshot을 기록한다.
     * 관련 Queue index 정리까지 한 원자 연산으로 수행해야 한다.
     */
    ReservationQueueTerminalResult completeSuccess(
            ReservationQueueWorkItem item,
            String workerId,
            ReservationQueueSuccessResult result,
            Instant completedAt
    );

    /**
     * worker owner가 일치하는 PROCESSING ticket에 공개 final 오류를 기록한다.
     * 내부 예외 메시지는 받지 않고 안정적인 오류 code만 저장한다.
     */
    ReservationQueueTerminalResult completeFinal(
            ReservationQueueWorkItem item,
            String workerId,
            String errorCode,
            Instant completedAt
    );

    /**
     * DB 호출 전에 거절된 Stream payload를 FAILED_FINAL 상태로 전환한다.
     * ticket identity가 복원되지 않으면 UNLINKED를 반환한다.
     */
    ReservationQueueTerminalResult failInvalid(
            ReservationQueueStreamMessage message,
            String errorCode,
            Instant completedAt
    );
}

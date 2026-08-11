package org.example.ticket.reservation.queue.repository;

import org.example.ticket.reservation.queue.dto.ReservationQueueRetryResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;

import java.time.Instant;

/** Retry 대기 전이와 due 작업의 Stream 재승격을 제공한다. */
public interface ReservationQueueRetryStore {

    /**
     * Worker가 소유한 PROCESSING ticket의 실패 횟수를 증가시키고 retry를 예약한다.
     * Budget을 소진하면 공개 final 오류로 종결한 결과를 반환한다.
     */
    ReservationQueueRetryResult schedule(
            ReservationQueueWorkItem item,
            String workerId,
            String errorCode,
            Instant failedAt
    );

    /**
     * 지정 회차에서 due가 된 RETRY_WAIT ticket을 새 Stream entry로 승격한다.
     * 반환값은 실제로 WAITING 상태로 바뀐 ticket 수다.
     */
    int promoteDue(long performanceTimeId, Instant now, int limit);
}

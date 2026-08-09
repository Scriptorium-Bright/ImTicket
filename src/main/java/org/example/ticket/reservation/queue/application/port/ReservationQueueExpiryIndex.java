package org.example.ticket.reservation.queue.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 만료 scanner가 순회할 performance time과 due ticket을 조회한다. */
public interface ReservationQueueExpiryIndex {

    List<Long> activePerformanceTimeIds();

    List<UUID> dueTicketIds(long performanceTimeId, Instant now, int limit);

    void removeStalePerformanceTimes(Instant now);
}

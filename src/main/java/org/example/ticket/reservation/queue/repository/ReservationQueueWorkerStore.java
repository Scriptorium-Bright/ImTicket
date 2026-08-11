package org.example.ticket.reservation.queue.repository;

import org.example.ticket.reservation.queue.dto.ReservationQueueClaimResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueStreamMessage;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Consumer Group 읽기와 ticket PROCESSING claim을 제공하는 Worker 저장소다. */
public interface ReservationQueueWorkerStore {

    /**
     * 회차 Stream에 지정한 Consumer Group이 존재하도록 보장한다.
     * 여러 인스턴스의 동시 생성은 기존 group 하나로 수렴해야 한다.
     */
    void ensureConsumerGroup(long performanceTimeId, String consumerGroup);

    /**
     * Consumer Group에서 아직 전달되지 않은 entry 한 건을 읽는다.
     * 지정 시간 안에 entry가 없으면 빈 Optional을 반환한다.
     */
    Optional<ReservationQueueStreamMessage> readNew(
            long performanceTimeId,
            String consumerGroup,
            String consumerName,
            Duration blockTimeout
    );

    /**
     * 최소 idle 시간이 지난 pending entry 한 건을 회수 후보로 조회한다.
     * 이 단계는 Consumer 소유권을 바꾸지 않으며 Redis ticket fencing을 먼저 수행하게 한다.
     */
    Optional<ReservationQueueStreamMessage> readStaleCandidate(
            long performanceTimeId,
            String consumerGroup,
            Duration minimumIdleTime
    );

    /**
     * WAITING ticket을 worker owner와 lease가 있는 PROCESSING 상태로 원자 전환한다.
     * Stream과 ticket payload가 일치하지 않으면 처리 소유권을 부여하지 않는다.
     */
    ReservationQueueClaimResult claim(
            ReservationQueueWorkItem item,
            String workerId,
            Instant claimedAt,
            Duration processingLease
    );

    /**
     * Lease가 끝난 PROCESSING ticket과 pending entry를 새 Worker에게 이전한다.
     * Terminal ticket이면 DB 실행 없이 ACK할 수 있는 결과를 반환한다.
     */
    ReservationQueueClaimResult recover(
            ReservationQueueWorkItem item,
            String consumerGroup,
            String workerId,
            Instant recoveredAt,
            Duration processingLease
    );

    /**
     * terminal 상태 저장이 끝난 Stream entry를 Consumer Group에서 ACK한다.
     * 반환값은 실제로 pending 목록에서 제거된 entry 수다.
     */
    long acknowledge(ReservationQueueWorkItem item, String consumerGroup);

    /**
     * ticket으로 복원할 수 없는 poison Stream entry를 직접 ACK한다.
     * 회차와 Stream ID만 신뢰할 수 있는 payload 거절 경로에서 사용한다.
     */
    long acknowledge(ReservationQueueStreamMessage message, String consumerGroup);
}

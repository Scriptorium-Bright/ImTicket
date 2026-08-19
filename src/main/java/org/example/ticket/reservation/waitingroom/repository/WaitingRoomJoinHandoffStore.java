package org.example.ticket.reservation.waitingroom.repository;

import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffRequest;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffState;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffSubmission;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Redis Stream과 request state의 저장 계약이다. */
public interface WaitingRoomJoinHandoffStore {

    /** 중복 owner를 수렴시키고 신규 request를 Redis Stream에 기록한다.
     * 보존 기간과 queue 상한을 저장 정책으로 적용한다. */
    WaitingRoomJoinHandoffSubmission enqueue(
            WaitingRoomJoinHandoffRequest request,
            Duration retention,
            int maxQueueLength
    );

    /** request Hash의 현재 상태를 조회한다.
     * 만료되거나 존재하지 않는 request는 빈 Optional로 반환한다. */
    Optional<WaitingRoomJoinHandoffState> find(long performanceTimeId, UUID requestId);

    /** worker가 처리 중인 request임을 기록한다.
     * request TTL도 함께 갱신한다. */
    void markProcessing(long performanceTimeId, UUID requestId, Duration retention);

    /** ticket 생성 결과와 완료 상태를 request Hash에 기록한다.
     * 이후 SSE 재연결이 ticket ID를 복구할 수 있다. */
    void markCompleted(long performanceTimeId, UUID requestId, UUID ticketId, Duration retention);

    /** worker 처리 실패 code와 재시도 가능 여부를 기록한다.
     * 실패 request도 SSE 재연결을 위해 보존한다. */
    void markFailed(long performanceTimeId, UUID requestId, String errorCode, boolean retryable, Duration retention);

    /** 회차 Stream에 consumer group이 존재하도록 보장한다.
     * 최초 생성 시 기존 Stream entry를 읽을 수 있는 offset을 사용한다. */
    void ensureConsumerGroup(long performanceTimeId);

    /** consumer group의 다음 미처리 Stream entry를 읽는다.
     * 읽은 entry는 pending 상태로 남아 worker 처리 후 acknowledge한다. */
    Optional<WaitingRoomJoinHandoffStreamRecord> readNext(long performanceTimeId);

    /** consumer group에서 여러 entry를 한 번에 읽는다.
     * 처리자 동시성만큼 읽어 Redis round trip과 polling 지연을 줄인다. */
    List<WaitingRoomJoinHandoffStreamRecord> readNextBatch(long performanceTimeId, int count);

    /** idle 시간이 지난 pending entry를 현재 consumer로 claim한다.
     * worker 재시작 뒤 미처리 request를 복구하는 데 사용한다. */
    Optional<WaitingRoomJoinHandoffStreamRecord> claimIdle(long performanceTimeId, Duration idleAfter);

    /** idle pending entry를 여러 건 회수한다.
     * 처리자 재시작 뒤 bounded batch로 복구한다. */
    List<WaitingRoomJoinHandoffStreamRecord> claimIdleBatch(
            long performanceTimeId,
            Duration idleAfter,
            int count
    );

    /** 처리 완료된 Stream entry를 consumer group에서 acknowledge한다.
     * acknowledge 전에는 장애 복구 대상 pending entry로 남는다. */
    void acknowledge(long performanceTimeId, String streamRecordId);
}

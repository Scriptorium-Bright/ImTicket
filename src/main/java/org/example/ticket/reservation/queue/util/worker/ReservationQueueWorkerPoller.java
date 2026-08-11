package org.example.ticket.reservation.queue.util.worker;

import lombok.extern.slf4j.Slf4j;
import org.example.ticket.reservation.queue.config.ReservationQueueWorkerProperties;
import org.example.ticket.reservation.queue.dto.ReservationQueueClaimResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueStreamMessage;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;
import org.example.ticket.reservation.queue.exception.ReservationQueuePayloadException;
import org.example.ticket.reservation.queue.repository.ReservationQueueExpiryIndex;
import org.example.ticket.reservation.queue.repository.ReservationQueueWorkerStore;
import org.example.ticket.reservation.queue.service.ReservationQueueWorkHandler;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** Active 회차를 순회하며 permit 범위 안에서 Stream 작업 한 건씩 전달하는 poller다. */
@Slf4j
public final class ReservationQueueWorkerPoller {

    private final ReservationQueueWorkerStore workerStore;
    private final ReservationQueueExpiryIndex expiryIndex;
    private final ReservationQueueStreamPayloadDecoder payloadDecoder;
    private final ReservationQueueWorkerPermits permits;
    private final ReservationQueueWorkHandler workHandler;
    private final Executor executor;
    private final ReservationQueueWorkerProperties properties;
    private final Duration processingLease;
    private final Clock clock;
    private final AtomicLong nextStart = new AtomicLong();

    /**
     * Stream intake에 필요한 저장소, decoder, permit과 실행기를 연결한다.
     * Poller는 DB 의존성을 받지 않고 WorkHandler 경계까지만 호출한다.
     */
    public ReservationQueueWorkerPoller(
            ReservationQueueWorkerStore workerStore,
            ReservationQueueExpiryIndex expiryIndex,
            ReservationQueueStreamPayloadDecoder payloadDecoder,
            ReservationQueueWorkerPermits permits,
            ReservationQueueWorkHandler workHandler,
            Executor executor,
            ReservationQueueWorkerProperties properties,
            Duration processingLease,
            Clock clock
    ) {
        this.workerStore = Objects.requireNonNull(workerStore, "workerStore must not be null");
        this.expiryIndex = Objects.requireNonNull(expiryIndex, "expiryIndex must not be null");
        this.payloadDecoder = Objects.requireNonNull(payloadDecoder, "payloadDecoder must not be null");
        this.permits = Objects.requireNonNull(permits, "permits must not be null");
        this.workHandler = Objects.requireNonNull(workHandler, "workHandler must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.processingLease = requirePositive(processingLease);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Active 회차마다 permit을 먼저 확보하고 새 Stream entry 한 건을 읽는다.
     * 정상 claim 또는 payload 거절 작업을 executor에 넘긴 횟수를 반환한다.
     */
    public int pollOnce() {
        List<Long> activePerformanceTimeIds = expiryIndex.activePerformanceTimeIds();
        if (activePerformanceTimeIds.isEmpty()) {
            return 0;
        }
        int dispatched = 0;
        int start = (int) Math.floorMod(nextStart.getAndIncrement(), activePerformanceTimeIds.size());
        for (int offset = 0; offset < activePerformanceTimeIds.size(); offset++) {
            Long performanceTimeId = activePerformanceTimeIds.get(
                    (start + offset) % activePerformanceTimeIds.size()
            );
            Optional<ReservationQueueWorkerPermits.Permit> acquired = permits.tryAcquire(performanceTimeId);
            if (acquired.isEmpty()) {
                continue;
            }
            ReservationQueueWorkerPermits.Permit permit = acquired.get();
            try {
                workerStore.ensureConsumerGroup(performanceTimeId, properties.consumerGroup());
                Optional<ReservationQueueStreamMessage> message = workerStore.readNew(
                        performanceTimeId,
                        properties.consumerGroup(),
                        properties.instanceId(),
                        properties.readBlockTimeout()
                );
                if (message.isEmpty()) {
                    permit.close();
                    continue;
                }
                if (decodeClaimAndDispatch(message.get(), permit)) {
                    dispatched++;
                }
            } catch (RuntimeException exception) {
                permit.close();
                log.warn(
                        "Reservation Queue Worker intake failed: performanceTimeId={}, cause={}",
                        performanceTimeId,
                        exception.getMessage()
                );
            }
        }
        return dispatched;
    }

    /**
     * 설정된 고정 간격마다 bounded polling 한 회를 실행한다.
     * Redis 조회 실패는 기록하고 다음 scheduler 실행에서 다시 시도한다.
     */
    @Scheduled(
            fixedDelayString = "${reservation.queue.worker.poll-interval:100ms}",
            scheduler = "reservationQueuePollScheduler"
    )
    public void pollScheduled() {
        try {
            pollOnce();
        } catch (RuntimeException exception) {
            log.warn("Reservation Queue Worker polling failed: {}", exception.getMessage());
        }
    }

    /**
     * Stream payload를 검증하고 Redis claim 결과에 따라 처리 작업을 제출한다.
     * payload 계약 위반은 DB handler 대신 명시적인 reject handler로 전달한다.
     */
    private boolean decodeClaimAndDispatch(
            ReservationQueueStreamMessage message,
            ReservationQueueWorkerPermits.Permit permit
    ) {
        try {
            ReservationQueueWorkItem item = payloadDecoder.decode(message);
            ReservationQueueClaimResult result = workerStore.claim(
                    item,
                    properties.instanceId(),
                    clock.instant(),
                    processingLease
            );
            if (result != ReservationQueueClaimResult.CLAIMED
                    && result != ReservationQueueClaimResult.ALREADY_OWNED) {
                permit.close();
                return false;
            }
            return submit(() -> workHandler.handle(item, properties.instanceId()), permit);
        } catch (ReservationQueuePayloadException exception) {
            return submit(() -> workHandler.reject(message, properties.instanceId(), exception), permit);
        }
    }

    /**
     * 작업 실행 뒤 permit을 항상 반환하는 wrapper를 executor에 제출한다.
     * 대기 queue가 없는 executor의 거절에서도 호출 thread가 permit을 즉시 반환한다.
     */
    private boolean submit(Runnable task, ReservationQueueWorkerPermits.Permit permit) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    permit.close();
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            permit.close();
            return false;
        }
    }

    /**
     * 처리 lease가 null이 아니며 양수인지 확인한다.
     * Redis PROCESSING claim에 잘못된 만료 시각이 기록되는 것을 막는다.
     */
    private Duration requirePositive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("processingLease must be positive");
        }
        return value;
    }
}

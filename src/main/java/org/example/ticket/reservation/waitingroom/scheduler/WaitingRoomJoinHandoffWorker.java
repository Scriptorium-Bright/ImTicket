package org.example.ticket.reservation.waitingroom.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinHandoffState;
import org.example.ticket.reservation.waitingroom.repository.WaitingRoomJoinHandoffStore;
import org.example.ticket.reservation.waitingroom.repository.WaitingRoomJoinHandoffStreamRecord;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomJoinHandoffService;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomJoinHandoffStatus;
import org.example.ticket.reservation.waitingroom.sse.WaitingRoomJoinHandoffLifecycleEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/** Redis Stream join request를 bounded concurrency로 처리하고 pending entry를 복구한다. */
@Slf4j
@Component
@ConditionalOnExpression("'${ticket.application.role:reservation}' == 'waiting-room' && '${reservation.waiting-room.async-join-enabled:false}' == 'true'")
public class WaitingRoomJoinHandoffWorker {

    private final WaitingRoomProperties properties;
    private final WaitingRoomJoinHandoffStore handoffStore;
    private final WaitingRoomJoinHandoffService handoffService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final ThreadPoolTaskExecutor workerExecutor;

    /** Redis Stream worker 의존성을 명시적으로 연결한다.
     * 전용 executor qualifier로 join worker와 다른 비동기 작업을 분리한다. */
    public WaitingRoomJoinHandoffWorker(
            WaitingRoomProperties properties,
            WaitingRoomJoinHandoffStore handoffStore,
            WaitingRoomJoinHandoffService handoffService,
            ApplicationEventPublisher eventPublisher,
            MeterRegistry meterRegistry,
            @Qualifier("waitingRoomJoinHandoffTaskExecutor") ThreadPoolTaskExecutor workerExecutor
    ) {
        this.properties = properties;
        this.handoffStore = handoffStore;
        this.handoffService = handoffService;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.workerExecutor = workerExecutor;
    }

    /** 활성 회차별 Stream에서 executor가 처리할 수 있는 만큼 request를 읽는다.
     * ticket sequence는 접수 시 Redis에서 예약되어 처리 완료 순서와 분리된다. */
    @Scheduled(fixedDelayString = "${reservation.waiting-room.join-handoff-poll-interval:50ms}")
    public void poll() {
        if (!properties.isAsyncJoinEnabled() || !properties.isEnabled()) {
            return;
        }
        for (Long performanceTimeId : properties.getEnabledPerformanceTimeIds()) {
            if (performanceTimeId == null || performanceTimeId <= 0) {
                continue;
            }
            try {
                handoffStore.ensureConsumerGroup(performanceTimeId);
                int slots = availableSlots();
                if (slots <= 0) {
                    continue;
                }
                List<WaitingRoomJoinHandoffStreamRecord> records = handoffStore.claimIdleBatch(
                        performanceTimeId,
                        properties.getJoinHandoffRecoveryAfter(),
                        slots
                );
                if (records.size() < slots) {
                    records = new java.util.ArrayList<>(records);
                    records.addAll(handoffStore.readNextBatch(
                            performanceTimeId,
                            slots - records.size()
                    ));
                }
                for (WaitingRoomJoinHandoffStreamRecord record : records) {
                    submit(record);
                }
            } catch (RuntimeException exception) {
                meterRegistry.counter("imticket.waiting-room.join-handoff.recovery", "result", "failure").increment();
                log.warn("Waiting Room join handoff poll failed: performanceTimeId={}", performanceTimeId, exception);
            }
        }
    }

    /** 읽은 Stream entry를 bounded worker executor에 제출한다.
     * executor 포화 시 entry는 pending 상태로 남아 recovery 대상이 된다. */
    private void submit(WaitingRoomJoinHandoffStreamRecord record) {
        try {
            workerExecutor.execute(() -> process(record));
        } catch (RejectedExecutionException exception) {
            meterRegistry.counter("imticket.waiting-room.join-handoff.requests", "result", "worker_rejected").increment();
        }
    }

    /** 접수 시 예약된 ticket을 완료 상태로 전환하고 lifecycle event를 발행한다.
     * 성공·업무 실패는 acknowledge하고 infrastructure 실패는 pending으로 남긴다. */
    private void process(WaitingRoomJoinHandoffStreamRecord record) {
        UUID requestId = record.request().requestId();
        long performanceTimeId = record.request().performanceTimeId();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Optional<WaitingRoomJoinHandoffState> state = handoffStore.find(performanceTimeId, requestId);
            if (state.isEmpty() || isTerminal(state.get().status())) {
                handoffStore.acknowledge(performanceTimeId, record.streamRecordId());
                return;
            }
            if (state.get().ticketId() == null) {
                throw new IllegalStateException("join handoff request has no reserved ticket");
            }
            handoffStore.markProcessing(performanceTimeId, requestId, handoffService.storageRetention());
            handoffStore.markCompleted(
                    performanceTimeId,
                    requestId,
                    state.get().ticketId(),
                    handoffService.storageRetention()
            );
            publish(new WaitingRoomJoinHandoffLifecycleEvent(
                    performanceTimeId,
                    requestId,
                    record.request().memberId(),
                    WaitingRoomJoinHandoffStatus.COMPLETED,
                    state.get().ticketId(),
                    null,
                    false,
                    Instant.now()
            ));
            handoffStore.acknowledge(performanceTimeId, record.streamRecordId());
            meterRegistry.counter("imticket.waiting-room.join-handoff.requests", "result", "completed").increment();
            sample.stop(meterRegistry.timer("imticket.waiting-room.join-handoff.ticket-creation", "result", "success"));
        } catch (BusinessException exception) {
            String errorCode = exception.getErrorCode().code();
            try {
                handoffStore.markFailed(
                        performanceTimeId,
                        requestId,
                        errorCode,
                        false,
                        handoffService.storageRetention()
                );
                publish(new WaitingRoomJoinHandoffLifecycleEvent(
                        performanceTimeId,
                        requestId,
                        record.request().memberId(),
                        WaitingRoomJoinHandoffStatus.FAILED,
                        null,
                        errorCode,
                        false,
                        Instant.now()
                ));
                handoffStore.acknowledge(performanceTimeId, record.streamRecordId());
                meterRegistry.counter("imticket.waiting-room.join-handoff.requests", "result", "failed").increment();
                sample.stop(meterRegistry.timer("imticket.waiting-room.join-handoff.ticket-creation", "result", "failure"));
            } catch (RuntimeException storageException) {
                log.warn("Waiting Room join handoff failure state could not be stored: requestId={}", requestId, storageException);
            }
        } catch (RuntimeException exception) {
            meterRegistry.counter("imticket.waiting-room.join-handoff.recovery", "result", "pending").increment();
            log.warn("Waiting Room join handoff processing remains pending: requestId={}", requestId, exception);
            sample.stop(meterRegistry.timer("imticket.waiting-room.join-handoff.ticket-creation", "result", "retry"));
        }
    }

    /** 완료 또는 실패 상태 변경을 application event로 발행한다.
     * publisher가 Redis Pub/Sub 전달을 담당한다. */
    private void publish(WaitingRoomJoinHandoffLifecycleEvent event) {
        eventPublisher.publishEvent(event);
    }

    /** request state가 재처리할 필요가 없는 terminal 상태인지 확인한다.
     * 완료·실패 entry는 중복 ticket 생성을 방지하기 위해 acknowledge한다. */
    private boolean isTerminal(WaitingRoomJoinHandoffStatus status) {
        return status == WaitingRoomJoinHandoffStatus.COMPLETED || status == WaitingRoomJoinHandoffStatus.FAILED;
    }

    /** executor의 active 작업과 대기 작업을 제외한 현재 제출 가능 수를 계산한다.
     * Redis Stream에서 읽은 entry가 executor queue에 무제한으로 쌓이지 않게 한다. */
    private int availableSlots() {
        int inFlight = workerExecutor.getActiveCount() + workerExecutor.getThreadPoolExecutor().getQueue().size();
        return Math.max(0, properties.getJoinHandoffWorkerConcurrency() - inFlight);
    }
}

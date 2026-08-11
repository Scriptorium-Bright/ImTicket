package org.example.ticket.reservation.queue.service;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.constant.ReservationFailureType;
import org.example.ticket.reservation.booking.dto.request.ReservationRequest;
import org.example.ticket.reservation.booking.dto.response.ReservationCreateResponse;
import org.example.ticket.reservation.booking.service.ReservationClaimExecutionService;
import org.example.ticket.reservation.booking.util.ReservationFailureClassifier;
import org.example.ticket.reservation.common.factory.ReservationIntentFingerprintFactory;
import org.example.ticket.reservation.common.value.ReservationIntentFingerprint;
import org.example.ticket.reservation.queue.config.ReservationQueueWorkerProperties;
import org.example.ticket.reservation.queue.constant.ReservationQueueErrorCode;
import org.example.ticket.reservation.queue.dto.ReservationQueueStreamMessage;
import org.example.ticket.reservation.queue.dto.ReservationQueueSuccessResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueTerminalResult;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;
import org.example.ticket.reservation.queue.exception.ReservationQueuePayloadException;
import org.example.ticket.reservation.queue.repository.ReservationQueueTerminalStore;
import org.example.ticket.reservation.queue.repository.ReservationQueueRetryStore;
import org.example.ticket.reservation.queue.repository.ReservationQueueWorkerStore;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.util.Objects;

/** PROCESSING Queue 작업을 공통 DB claim 실행과 Redis terminal, ACK 순서로 연결한다. */
public final class ReservationQueueProcessor implements ReservationQueueWorkHandler {

    private final ReservationClaimExecutionService executionService;
    private final MemberRepository memberRepository;
    private final ReservationFailureClassifier failureClassifier;
    private final ReservationQueueTerminalStore terminalStore;
    private final ReservationQueueRetryStore retryStore;
    private final ReservationQueueWorkerStore workerStore;
    private final ReservationQueueWorkerProperties workerProperties;
    private final Clock clock;

    /**
     * DB 예약 실행과 Redis terminal 저장에 필요한 의존성을 연결한다.
     * MySQL transaction은 기존 execution service 안에서 끝나며 Redis 호출은 그 이후에 수행된다.
     */
    public ReservationQueueProcessor(
            ReservationClaimExecutionService executionService,
            MemberRepository memberRepository,
            ReservationFailureClassifier failureClassifier,
            ReservationQueueTerminalStore terminalStore,
            ReservationQueueRetryStore retryStore,
            ReservationQueueWorkerStore workerStore,
            ReservationQueueWorkerProperties workerProperties,
            Clock clock
    ) {
        this.executionService = Objects.requireNonNull(executionService, "executionService must not be null");
        this.memberRepository = Objects.requireNonNull(memberRepository, "memberRepository must not be null");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier must not be null");
        this.terminalStore = Objects.requireNonNull(terminalStore, "terminalStore must not be null");
        this.retryStore = Objects.requireNonNull(retryStore, "retryStore must not be null");
        this.workerStore = Objects.requireNonNull(workerStore, "workerStore must not be null");
        this.workerProperties = Objects.requireNonNull(workerProperties, "workerProperties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Queue payload를 기존 예약 요청으로 변환해 공통 DB claim 실행 경계를 호출한다.
     * 성공 또는 공개 final 상태를 Redis에 저장한 뒤에만 Stream entry를 ACK한다.
     */
    @Override
    public void handle(ReservationQueueWorkItem item, String workerId) {
        Objects.requireNonNull(item, "item must not be null");
        requireWorker(workerId);
        ReservationRequest request = new ReservationRequest(
                item.performanceTimeId(),
                item.payload().normalizedSeatIds()
        );
        ReservationIntentFingerprint fingerprint = ReservationIntentFingerprintFactory.create(
                item.performanceTimeId(),
                item.payload().normalizedSeatIds()
        );

        ReservationCreateResponse response;
        try {
            response = executionService.execute(
                    item.payload().memberId(),
                    item.payload().idempotencyKey().value(),
                    request,
                    fingerprint
            );
        } catch (BusinessException exception) {
            handleBusinessFailure(item, workerId, exception);
            return;
        } catch (DataIntegrityViolationException exception) {
            handleIntegrityFailure(item, workerId, exception);
            return;
        } catch (RuntimeException exception) {
            handleRuntimeFailure(item, workerId, exception);
            return;
        }
        completeSuccess(item, workerId, response);
    }

    /**
     * Decoder가 DB 호출 전에 거절한 Stream payload를 공개 Queue 오류로 닫는다.
     * ticket과 연결된 final 전이가 성공하거나 연결 불가능한 poison entry인 경우 ACK한다.
     */
    @Override
    public void reject(
            ReservationQueueStreamMessage message,
            String workerId,
            ReservationQueuePayloadException exception
    ) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(exception, "exception must not be null");
        requireWorker(workerId);
        String errorCode = switch (exception.reason()) {
            case UNSUPPORTED_SCHEMA -> ReservationQueueErrorCode.WORKER_PAYLOAD_UNSUPPORTED.code();
            case INVALID_FIELD, FINGERPRINT_MISMATCH -> ReservationQueueErrorCode.WORKER_PAYLOAD_INVALID.code();
        };
        ReservationQueueTerminalResult result = terminalStore.failInvalid(
                message,
                errorCode,
                clock.instant()
        );
        if (result.allowsAck() || result == ReservationQueueTerminalResult.UNLINKED) {
            workerStore.acknowledge(message, workerProperties.consumerGroup());
        }
    }

    /**
     * DB 예약 응답을 Queue용 versioned 성공 결과로 변환하고 terminal 저장을 시도한다.
     * 같은 결과로 이미 완료된 재전달도 ACK 가능한 멱등 결과로 처리한다.
     */
    private void completeSuccess(
            ReservationQueueWorkItem item,
            String workerId,
            ReservationCreateResponse response
    ) {
        ReservationQueueSuccessResult result = new ReservationQueueSuccessResult(
                ReservationQueueSuccessResult.CURRENT_SCHEMA_VERSION,
                response.getId(),
                response.getTotalPrice(),
                response.getOrderUid(),
                response.getExpiredTime()
        );
        ReservationQueueTerminalResult terminal = terminalStore.completeSuccess(
                item,
                workerId,
                result,
                clock.instant()
        );
        acknowledgeIfTerminal(item, terminal);
    }

    /**
     * 예약 비즈니스 오류를 final, retryable과 lease 보호 분류로 나눈다.
     * 공개 final 오류만 Redis에 기록하며 나머지는 pending entry와 PROCESSING 상태를 유지한다.
     */
    private void handleBusinessFailure(
            ReservationQueueWorkItem item,
            String workerId,
            BusinessException exception
    ) {
        ReservationFailureType failureType = failureClassifier.classify(exception);
        if (failureType == ReservationFailureType.RETRYABLE) {
            acknowledgeIfRetryScheduled(
                    item,
                    retryStore.schedule(item, workerId, stableErrorCode(exception), clock.instant())
            );
            return;
        }
        if (failureType != ReservationFailureType.FINAL) {
            return;
        }
        ReservationErrorCode errorCode = failureClassifier.requireFinalErrorCode(exception);
        completeFinal(item, workerId, errorCode.code());
    }

    /**
     * Spring transient DB 오류를 retry ZSET으로 보내고 나머지 런타임 오류는 전달한다.
     * 원인을 판정할 수 없는 오류는 processing lease와 pending entry를 유지한다.
     */
    private void handleRuntimeFailure(
            ReservationQueueWorkItem item,
            String workerId,
            RuntimeException exception
    ) {
        if (failureClassifier.classify(exception) != ReservationFailureType.RETRYABLE) {
            throw exception;
        }
        acknowledgeIfRetryScheduled(
                item,
                retryStore.schedule(item, workerId, stableErrorCode(exception), clock.instant())
        );
    }

    /**
     * Retry ZSET 또는 budget 초과 terminal이 저장된 작업만 ACK한다.
     * Owner와 상태 불일치는 pending으로 남겨 현재 소유자가 후속 판단을 수행하게 한다.
     */
    private void acknowledgeIfRetryScheduled(
            ReservationQueueWorkItem item,
            org.example.ticket.reservation.queue.dto.ReservationQueueRetryResult result
    ) {
        if (result.allowsAck()) {
            workerStore.acknowledge(item, workerProperties.consumerGroup());
        }
    }

    /**
     * Retry 기록에 사용할 공개 예약 오류 code를 반환한다.
     * BusinessException이 아닌 일시 오류는 고정된 내부 분류 code로 제한한다.
     */
    private String stableErrorCode(RuntimeException exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException.getErrorCode().code();
        }
        return "QUEUE_TRANSIENT_PROCESSING_FAILURE";
    }

    /**
     * DB claim 생성의 무결성 오류가 삭제된 회원 때문인지 한 번 확인한다.
     * 회원이 없으면 공개 final로 수렴시키고 다른 무결성 오류는 pending 복구 대상으로 전달한다.
     */
    private void handleIntegrityFailure(
            ReservationQueueWorkItem item,
            String workerId,
            DataIntegrityViolationException exception
    ) {
        if (!memberRepository.existsById(item.payload().memberId())) {
            completeFinal(item, workerId, ReservationErrorCode.RESERVATION_MEMBER_NOT_FOUND.code());
            return;
        }
        throw exception;
    }

    /**
     * 공개 final 오류 code를 Redis ticket에 기록한다.
     * Terminal 저장이 확인된 경우에만 해당 Stream entry를 ACK한다.
     */
    private void completeFinal(ReservationQueueWorkItem item, String workerId, String errorCode) {
        ReservationQueueTerminalResult terminal = terminalStore.completeFinal(
                item,
                workerId,
                errorCode,
                clock.instant()
        );
        acknowledgeIfTerminal(item, terminal);
    }

    /**
     * 신규 terminal 또는 같은 terminal의 멱등 재호출에서 XACK를 실행한다.
     * owner 불일치와 상태 불일치는 pending 상태로 남겨 recovery가 판단하게 한다.
     */
    private void acknowledgeIfTerminal(
            ReservationQueueWorkItem item,
            ReservationQueueTerminalResult terminal
    ) {
        if (terminal.allowsAck()) {
            workerStore.acknowledge(item, workerProperties.consumerGroup());
        }
    }

    /**
     * Poller가 전달한 worker ID가 현재 설정 identity와 일치하는지 확인한다.
     * 다른 인스턴스 identity로 terminal 전이를 시도하는 호출을 차단한다.
     */
    private void requireWorker(String workerId) {
        if (!workerProperties.instanceId().equals(workerId)) {
            throw new IllegalArgumentException("workerId does not match configured instanceId");
        }
    }
}

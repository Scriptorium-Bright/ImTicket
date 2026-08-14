package org.example.ticket.reservation.booking.service;

import lombok.extern.slf4j.Slf4j;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.constant.ReservationFailureType;
import org.example.ticket.reservation.booking.domain.ReservationIdempotencyStatus;
import org.example.ticket.reservation.booking.dto.ReservationClaimSnapshot;
import org.example.ticket.reservation.booking.dto.request.ReservationRequest;
import org.example.ticket.reservation.booking.dto.response.ReservationCreateResponse;
import org.example.ticket.reservation.booking.exception.ReservationSnapshotException;
import org.example.ticket.reservation.booking.util.ReservationFailureClassifier;
import org.example.ticket.reservation.booking.util.ReservationFailureSnapshotCodec;
import org.example.ticket.reservation.booking.util.ReservationResponseSnapshotCodec;
import org.example.ticket.reservation.booking.util.admission.SeatAdmissionService;
import org.example.ticket.reservation.booking.util.idempotency.ReservationIntentFingerprint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/** 동기 API와 Queue Worker가 공유하는 MySQL 예약 claim 실행 경계다. */
@Service
@Slf4j
public class ReservationClaimExecutionService {

    private static final String MEMBER_KEY_CONSTRAINT = "uk_reservation_idempotency_member_key";
    private static final int RESOLUTION_ATTEMPTS = 4;

    private final ReservationIdempotencyTransactionService transactionService;
    private final ReservationIdempotentCreationService creationService;
    private final SeatAdmissionService seatAdmissionService;
    private final ReservationFailureClassifier failureClassifier;
    private final ReservationResponseSnapshotCodec responseSnapshotCodec;
    private final ReservationFailureSnapshotCodec failureSnapshotCodec;
    private final long processingLeaseSeconds;

    /**
     * Claim transaction, 예약 생성과 실패 snapshot 의존성을 초기화한다.
     * DB 처리 lease는 1초 이상이어야 하며 Queue lease 관계는 Queue 설정 단계에서 추가 검증한다.
     */
    public ReservationClaimExecutionService(
            ReservationIdempotencyTransactionService transactionService,
            ReservationIdempotentCreationService creationService,
            SeatAdmissionService seatAdmissionService,
            ReservationFailureClassifier failureClassifier,
            ReservationResponseSnapshotCodec responseSnapshotCodec,
            ReservationFailureSnapshotCodec failureSnapshotCodec,
            @Value("${reservation.idempotency.processing-lease-seconds:30}") long processingLeaseSeconds
    ) {
        if (processingLeaseSeconds < 1) {
            throw new IllegalArgumentException("reservation.idempotency.processing-lease-seconds는 1 이상이어야 합니다.");
        }
        this.transactionService = transactionService;
        this.creationService = creationService;
        this.seatAdmissionService = seatAdmissionService;
        this.failureClassifier = failureClassifier;
        this.responseSnapshotCodec = responseSnapshotCodec;
        this.failureSnapshotCodec = failureSnapshotCodec;
        this.processingLeaseSeconds = processingLeaseSeconds;
    }

    /**
     * 검증된 회원 ID와 canonical key로 claim을 선점하고 좌석 admission과 예약 생성을 실행한다.
     * 기존 claim은 요청 hash, 상태와 lease를 검사해 성공 또는 최종 실패를 재생하거나 조건부 회수한다.
     */
    public ReservationCreateResponse execute (
            Long memberId,
            String idempotencyKey,
            ReservationRequest request,
            ReservationIntentFingerprint requestFingerprint
    ) {
        if (memberId == null || memberId <= 0) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_MEMBER_NOT_FOUND);
        }

        String attemptToken = newAttemptToken();
        LocalDateTime now = LocalDateTime.now();
        ReservationClaimSnapshot claim;

        try {
            claim = transactionService.createClaim(
                    memberId,
                    idempotencyKey,
                    requestFingerprint.requestHash(),
                    attemptToken,
                    leaseUntil(now)
            );
        } catch (DataIntegrityViolationException exception) {
            if (!isMemberKeyDuplicate(exception)) {
                throw exception;
            }
            return resolveExisting(memberId, idempotencyKey, request, requestFingerprint);
        }

        return executeOwnedClaim(memberId, request, requestFingerprint, claim.id(), attemptToken);
    }

    /** 만료·완료 Waiting Room pass에서 기존 최종 snapshot만 조회해 재생한다.
     * 새 claim 생성, retryable claim 회수와 좌석 admission은 이 경로에서 수행하지 않는다. */
    public ReservationCreateResponse replayOnly(
            Long memberId,
            String idempotencyKey,
            ReservationIntentFingerprint requestFingerprint
    ) {
        if (memberId == null || memberId <= 0) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_MEMBER_NOT_FOUND);
        }

        ReservationClaimSnapshot existing = transactionService.findExisting(memberId, idempotencyKey)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.IDEMPOTENCY_REPLAY_ONLY));
        if (!existing.requestHash().equals(requestFingerprint.requestHash())) {
            throw new BusinessException(ReservationErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (existing.status() == ReservationIdempotencyStatus.SUCCEEDED) {
            return responseSnapshotCodec.decode(
                    existing.responseSchemaVersion(),
                    existing.responsePayload()
            );
        }
        if (existing.status() == ReservationIdempotencyStatus.FAILED_FINAL) {
            ReservationErrorCode errorCode = failureSnapshotCodec.decode(
                    existing.failureSchemaVersion(),
                    existing.lastErrorCode()
            );
            throw new BusinessException(errorCode);
        }
        throw new BusinessException(ReservationErrorCode.IDEMPOTENCY_REPLAY_ONLY);
    }

    /**
     * 고유 키 충돌로 발견한 기존 claim의 replay 또는 reclaim 경로를 결정한다.
     * Final 상태는 저장된 공개 오류를 재생하고 reclaim 후보에서 제외한다.
     */
    private ReservationCreateResponse resolveExisting(
            Long memberId,
            String idempotencyKey,
            ReservationRequest request,
            ReservationIntentFingerprint requestFingerprint
    ) {
        for (int attempt = 0; attempt < RESOLUTION_ATTEMPTS; attempt++) {
            ReservationClaimSnapshot existing = transactionService
                    .findExisting(memberId, idempotencyKey)
                    .orElseThrow(() -> new ReservationSnapshotException("중복 claim의 기존 행을 찾을 수 없습니다."));

            if (!existing.requestHash().equals(requestFingerprint.requestHash())) {
                throw new BusinessException(ReservationErrorCode.IDEMPOTENCY_CONFLICT);
            }
            if (existing.status() == ReservationIdempotencyStatus.SUCCEEDED) {
                return responseSnapshotCodec.decode(
                        existing.responseSchemaVersion(),
                        existing.responsePayload()
                );
            }
            if (existing.status() == ReservationIdempotencyStatus.FAILED_FINAL) {
                ReservationErrorCode errorCode = failureSnapshotCodec.decode(
                        existing.failureSchemaVersion(),
                        existing.lastErrorCode()
                );
                throw new BusinessException(errorCode);
            }

            LocalDateTime now = LocalDateTime.now();
            if (existing.status() == ReservationIdempotencyStatus.PROCESSING
                    && existing.leaseExpiresAt() != null
                    && existing.leaseExpiresAt().isAfter(now)) {
                throw new BusinessException(ReservationErrorCode.IDEMPOTENCY_PROCESSING);
            }

            String attemptToken = newAttemptToken();
            if (transactionService.tryReclaim(
                    existing.id(),
                    requestFingerprint.requestHash(),
                    attemptToken,
                    now,
                    leaseUntil(now)
            )) {
                return executeOwnedClaim(
                        memberId,
                        request,
                        requestFingerprint,
                        existing.id(),
                        attemptToken
                );
            }
        }
        throw new BusinessException(ReservationErrorCode.IDEMPOTENCY_PROCESSING);
    }

    /**
     * 현재 attempt가 소유한 claim을 좌석 admission 범위 안에서 실행한다.
     * 실행 실패는 분류 결과에 따라 retryable 또는 final DB 상태로 기록한 뒤 원래 예외를 전달한다.
     */
    private ReservationCreateResponse executeOwnedClaim(
            Long memberId,
            ReservationRequest request,
            ReservationIntentFingerprint requestFingerprint,
            Long claimId,
            String attemptToken
    ) {
        try {
            return seatAdmissionService.execute(
                    request,
                    () -> creationService.create(
                            memberId,
                            request,
                            requestFingerprint.requestHash(),
                            claimId,
                            attemptToken
                    )
            );
        } catch (RuntimeException exception) {
            recordFailureWithoutMaskingOriginal(claimId, attemptToken, exception);
            throw exception;
        }
    }

    /**
     * 실패 분류에 대응하는 claim 상태를 독립 transaction으로 기록한다.
     * 상태 기록 실패는 suppressed 예외와 로그에 남기고 최초 예약 예외를 호출자에게 유지한다.
     */
    private void recordFailureWithoutMaskingOriginal(
            Long claimId,
            String attemptToken,
            RuntimeException original
    ) {
        ReservationFailureType failureType = failureClassifier.classify(original);
        if (failureType == ReservationFailureType.LEASE_GUARDED) {
            return;
        }

        try {
            if (failureType == ReservationFailureType.RETRYABLE) {
                transactionService.markRetryableFailureIfOwned(
                        claimId,
                        attemptToken,
                        retryableFailureCode(original),
                        LocalDateTime.now()
                );
                return;
            }

            ReservationErrorCode errorCode = failureClassifier.requireFinalErrorCode(original);
            transactionService.markFinalFailureIfOwned(
                    claimId,
                    attemptToken,
                    failureSnapshotCodec.encode(errorCode),
                    ReservationFailureSnapshotCodec.CURRENT_SCHEMA_VERSION,
                    LocalDateTime.now()
            );
        } catch (RuntimeException markingFailure) {
            original.addSuppressed(markingFailure);
            log.error("예약 멱등성 claim 실패 상태를 기록하지 못했습니다. claimId={}", claimId, markingFailure);
        }
    }

    /**
     * 재시도 가능 claim에 기록할 짧고 안정적인 실패 식별자를 만든다.
     * 예약 오류 code를 우선 사용하고 일반 예외는 클래스명을 64자로 제한한다.
     */
    private String retryableFailureCode(RuntimeException exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException.getErrorCode().code();
        }
        String simpleName = exception.getClass().getSimpleName();
        return simpleName.length() <= 64 ? simpleName : simpleName.substring(0, 64);
    }

    /**
     * 예외 원인 체인에서 사용자별 멱등 키 고유 제약 위반을 찾는다.
     * MySQL과 PostgreSQL 오류 형식을 해석해 기존 claim 해결 경로로 전환한다.
     */
    private boolean isMemberKeyDuplicate(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (current instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == 1062 || "23505".equals(sqlException.getSQLState()))
                    && message != null
                    && message.toLowerCase(Locale.ROOT).contains(MEMBER_KEY_CONSTRAINT)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 기준 시각에 설정된 DB claim 처리 lease를 더한다.
     * 새 PROCESSING attempt가 소유권을 유지할 만료 시각을 반환한다.
     */
    private LocalDateTime leaseUntil(LocalDateTime now) {
        return now.plusSeconds(processingLeaseSeconds);
    }

    /**
     * claim의 현재 처리 소유자를 구분할 UUID attempt token을 만든다.
     * 이전 실행이 새 소유자의 상태를 변경하지 못하게 하는 fencing 값으로 사용한다.
     */
    private String newAttemptToken() {
        return UUID.randomUUID().toString();
    }
}

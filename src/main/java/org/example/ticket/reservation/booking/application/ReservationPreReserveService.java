package org.example.ticket.reservation.booking.application;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.reservation.booking.concurrency.SeatAdmissionService;
import org.example.ticket.reservation.booking.application.ReservationRequestFingerprint;
import org.example.ticket.reservation.booking.application.ReservationClaimSnapshot;
import org.example.ticket.reservation.booking.domain.ReservationErrorCode;
import org.example.ticket.reservation.booking.support.ReservationSnapshotException;
import org.example.ticket.reservation.booking.domain.ReservationIdempotencyStatus;
import org.example.ticket.reservation.booking.api.ReservationRequest;
import org.example.ticket.reservation.booking.api.ReservationCreateResponse;
import org.example.ticket.reservation.booking.support.ReservationRequestHasher;
import org.example.ticket.reservation.booking.support.ReservationResponseSnapshotCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
@Slf4j
public class ReservationPreReserveService {

    private static final String MEMBER_KEY_CONSTRAINT = "uk_reservation_idempotency_member_key";
    private static final int RESOLUTION_ATTEMPTS = 4;

    private final MemberRepository memberRepository;
    private final ReservationRequestHasher requestHasher;
    private final ReservationIdempotencyTransactionService transactionService;
    private final ReservationIdempotentCreationService creationService;
    private final ReservationResponseSnapshotCodec snapshotCodec;
    private final SeatAdmissionService seatAdmissionService;
    private final long processingLeaseSeconds;

    /**
     * 멱등성 처리에 필요한 의존성과 처리 lease 시간을 초기화한다.
     * lease는 처리 중인 요청을 다른 요청이 회수하기까지의 최소 보유 시간이며 1초 이상이어야 한다.
     */
    public ReservationPreReserveService(
            MemberRepository memberRepository,
            ReservationRequestHasher requestHasher,
            ReservationIdempotencyTransactionService transactionService,
            ReservationIdempotentCreationService creationService,
            ReservationResponseSnapshotCodec snapshotCodec,
            SeatAdmissionService seatAdmissionService,
            @Value("${reservation.idempotency.processing-lease-seconds:30}") long processingLeaseSeconds
    ) {
        if (processingLeaseSeconds < 1) {
            throw new IllegalArgumentException("reservation.idempotency.processing-lease-seconds는 1 이상이어야 합니다.");
        }
        this.memberRepository = memberRepository;
        this.requestHasher = requestHasher;
        this.transactionService = transactionService;
        this.creationService = creationService;
        this.snapshotCodec = snapshotCodec;
        this.seatAdmissionService = seatAdmissionService;
        this.processingLeaseSeconds = processingLeaseSeconds;
    }

    /**
     * 예매 생성 요청의 멱등성 키와 본문을 정규화해 claim을 선점한 뒤 예약 생성을 수행한다.
     * 같은 키의 요청은 기존 성공 응답을 재생하거나, 만료·재시도 가능 claim을 회수해 처리한다.
     */
    public ReservationCreateResponse preReserve(
            String walletAddress,
            String rawIdempotencyKey,
            ReservationRequest request
    ) {
        String idempotencyKey = requestHasher.normalizeKey(rawIdempotencyKey);
        ReservationRequestFingerprint requestFingerprint = requestHasher.fingerprint(request);
        Long memberId = memberRepository.findIdByWalletAddressIgnoreCase(walletAddress)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

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
            return resolveExisting(memberId, walletAddress, idempotencyKey, requestFingerprint, request);
        }

        return executeOwnedClaim(memberId, walletAddress, request, requestFingerprint, claim.id(), attemptToken);
    }

    /**
     * 고유 키 충돌로 발견한 기존 claim을 해석한다.
     * 요청 본문이 다르면 충돌로 거절하고, 성공 결과는 재생하며, 처리 lease가 끝난 claim만 조건부 회수한다.
     */
    private ReservationCreateResponse resolveExisting(
            Long memberId,
            String walletAddress,
            String idempotencyKey,
            ReservationRequestFingerprint requestFingerprint,
            ReservationRequest request
    ) {
        for (int attempt = 0; attempt < RESOLUTION_ATTEMPTS; attempt++) {
            ReservationClaimSnapshot existing = transactionService
                    .findExisting(memberId, idempotencyKey)
                    .orElseThrow(() -> new ReservationSnapshotException("중복 claim의 기존 행을 찾을 수 없습니다."));

            if (!existing.requestHash().equals(requestFingerprint.requestHash())) {
                throw new BusinessException(ReservationErrorCode.IDEMPOTENCY_CONFLICT);
            }
            if (existing.status() == ReservationIdempotencyStatus.SUCCEEDED) {
                return snapshotCodec.decode(existing.responseSchemaVersion(), existing.responsePayload());
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
                        walletAddress,
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
     * 현재 attempt가 소유한 claim에 대해 좌석 admission gate를 통과한 뒤 실제 예약 생성 트랜잭션을 실행한다.
     * 즉시 재시도 가능한 실패는 원래 예외를 보존한 채 claim 상태에도 기록한다.
     */
    private ReservationCreateResponse executeOwnedClaim(
            Long memberId,
            String walletAddress,
            ReservationRequest request,
            ReservationRequestFingerprint requestFingerprint,
            Long claimId,
            String attemptToken
    ) {
        try {
            return seatAdmissionService.execute(
                    request,
                    () -> creationService.create(
                            memberId,
                            walletAddress,
                            request,
                            requestFingerprint.requestHash(),
                            claimId,
                            attemptToken
                    )
            );
        } catch (RuntimeException exception) {
            markFailedWithoutMaskingOriginal(claimId, attemptToken, exception);
            throw exception;
        }
    }

    /**
     * 재시도 가능한 실패를 claim에 기록하되, 기록 자체가 실패해도 최초 예외가 호출자에게 그대로 전달되게 한다.
     */
    private void markFailedWithoutMaskingOriginal(Long claimId, String attemptToken, RuntimeException original) {
        if (!isImmediatelyRetryable(original)) {
            return;
        }
        try {
            transactionService.markFailedIfOwned(
                    claimId,
                    attemptToken,
                    failureCode(original),
                    LocalDateTime.now()
            );
        } catch (RuntimeException markingFailure) {
            original.addSuppressed(markingFailure);
            log.error("예약 멱등성 claim 실패 상태를 기록하지 못했습니다. claimId={}", claimId, markingFailure);
        }
    }

    /** admission 거절·좌석 잠금 시간 초과·일시적 DB 오류처럼 곧바로 재시도할 수 있는 실패인지 판단한다. */
    private boolean isImmediatelyRetryable(RuntimeException exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException.getErrorCode() == ReservationErrorCode.SEAT_ADMISSION_REJECTED
                    || businessException.getErrorCode() == ReservationErrorCode.SEAT_LOCK_TIMEOUT;
        }
        Throwable current = exception;
        while (current != null) {
            if (current instanceof TransientDataAccessException
                    || current instanceof jakarta.persistence.LockTimeoutException
                    || current instanceof jakarta.persistence.PessimisticLockException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** claim에 저장할 짧고 안정적인 실패 식별자를 도메인 오류 코드 또는 예외 클래스명으로 만든다. */
    private String failureCode(RuntimeException exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException.getErrorCode().code();
        }
        String simpleName = exception.getClass().getSimpleName();
        return simpleName.length() <= 64 ? simpleName : simpleName.substring(0, 64);
    }

    /** 예외 원인 체인을 따라 회원별 멱등성 키의 고유 제약 위반인지 DB 종류와 무관하게 식별한다. */
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

    /** 주어진 기준 시각부터 설정된 처리 lease만큼 유효한 만료 시각을 계산한다. */
    private LocalDateTime leaseUntil(LocalDateTime now) {
        return now.plusSeconds(processingLeaseSeconds);
    }

    /** claim의 현재 처리 소유자를 구분하기 위한 고유 attempt token을 생성한다. */
    private String newAttemptToken() {
        return UUID.randomUUID().toString();
    }
}

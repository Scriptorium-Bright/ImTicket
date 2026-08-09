package org.example.ticket.reservation.booking.application;

import lombok.RequiredArgsConstructor;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.reservation.booking.application.ReservationClaimSnapshot;
import org.example.ticket.reservation.booking.domain.ReservationIdempotency;
import org.example.ticket.reservation.booking.domain.ReservationIdempotencyStatus;
import org.example.ticket.reservation.booking.persistence.ReservationIdempotencyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReservationIdempotencyTransactionService {

    private final ReservationIdempotencyRepository idempotencyRepository;
    private final MemberRepository memberRepository;

    /**
     * 새 독립 트랜잭션에서 처리 중인 멱등성 claim을 즉시 저장하고, 고유 키 충돌을 호출자에게 전달한다.
     * 이후 예약 생성 트랜잭션이 실패해도 최초 claim과 lease 정보는 조회·복구할 수 있다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReservationClaimSnapshot createClaim(
            Long memberId,
            String idempotencyKey,
            String requestHash,
            String attemptToken,
            LocalDateTime leaseExpiresAt
    ) {
        Member member = memberRepository.getReferenceById(memberId);
        ReservationIdempotency claim = idempotencyRepository.saveAndFlush(
                ReservationIdempotency.builder()
                        .member(member)
                        .idempotencyKey(idempotencyKey)
                        .requestHash(requestHash)
                        .status(ReservationIdempotencyStatus.PROCESSING)
                        .attemptToken(attemptToken)
                        .leaseExpiresAt(leaseExpiresAt)
                        .build()
        );
        return ReservationClaimSnapshot.from(claim);
    }

    /** 독립 읽기 트랜잭션에서 회원과 멱등성 키에 대응하는 claim의 현재 스냅샷을 조회한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ReservationClaimSnapshot> findExisting(Long memberId, String idempotencyKey) {
        return idempotencyRepository.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey)
                .map(ReservationClaimSnapshot::from);
    }

    /**
     * 만료된 처리 lease 또는 재시도 가능 실패 claim을 현재 요청의 attempt token으로 조건부 회수한다.
     * 반환값이 {@code true}일 때만 호출자가 새 처리 소유자가 된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryReclaim(
            Long claimId,
            String requestHash,
            String attemptToken,
            LocalDateTime now,
            LocalDateTime leaseExpiresAt
    ) {
        return idempotencyRepository.tryReclaim(
                claimId,
                requestHash,
                attemptToken,
                leaseExpiresAt,
                now,
                ReservationIdempotencyStatus.PROCESSING,
                ReservationIdempotencyStatus.FAILED_RETRYABLE
        ) == 1;
    }

    /**
     * 현재 attempt token이 소유한 처리 중 claim만 재시도 가능 실패로 전환한다.
     * 이미 다른 요청이 회수했거나 성공 처리한 claim은 변경하지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailedIfOwned(
            Long claimId,
            String attemptToken,
            String errorCode,
            LocalDateTime failedAt
    ) {
        return idempotencyRepository.markFailedIfOwned(
                claimId,
                attemptToken,
                errorCode,
                failedAt,
                ReservationIdempotencyStatus.PROCESSING,
                ReservationIdempotencyStatus.FAILED_RETRYABLE
        ) == 1;
    }

}

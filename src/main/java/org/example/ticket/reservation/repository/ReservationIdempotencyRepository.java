package org.example.ticket.reservation.repository;

import jakarta.persistence.LockModeType;
import org.example.ticket.reservation.model.ReservationIdempotency;
import org.example.ticket.reservation.model.ReservationIdempotencyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ReservationIdempotencyRepository extends JpaRepository<ReservationIdempotency, Long> {

    /** 사용자와 멱등성 키 조합으로 기존 claim을 조회한다. */
    Optional<ReservationIdempotency> findByMemberIdAndIdempotencyKey(Long memberId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ri from ReservationIdempotency ri where ri.id = :id")
    /** claim 상태를 변경하기 전에 해당 idempotency row를 배타적으로 조회한다. */
    Optional<ReservationIdempotency> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ReservationIdempotency ri
            set ri.status = :processing,
                ri.attemptToken = :attemptToken,
                ri.leaseExpiresAt = :leaseExpiresAt,
                ri.lastErrorCode = null,
                ri.version = ri.version + 1
            where ri.id = :id
              and ri.requestHash = :requestHash
              and (
                    ri.status = :failedRetryable
                    or (ri.status = :processing and ri.leaseExpiresAt <= :now)
              )
            """)
    /** 만료되었거나 재시도 가능한 claim을 다시 PROCESSING으로 선점한다. */
    int tryReclaim(
            @Param("id") Long id,
            @Param("requestHash") String requestHash,
            @Param("attemptToken") String attemptToken,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
            @Param("now") LocalDateTime now,
            @Param("processing") ReservationIdempotencyStatus processing,
            @Param("failedRetryable") ReservationIdempotencyStatus failedRetryable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ReservationIdempotency ri
            set ri.status = :failedRetryable,
                ri.leaseExpiresAt = :failedAt,
                ri.lastErrorCode = :errorCode,
                ri.version = ri.version + 1
            where ri.id = :id
              and ri.status = :processing
              and ri.attemptToken = :attemptToken
            """)
    /** 현재 attempt token을 소유한 처리만 재시도 가능한 실패 상태로 전환한다. */
    int markFailedIfOwned(
            @Param("id") Long id,
            @Param("attemptToken") String attemptToken,
            @Param("errorCode") String errorCode,
            @Param("failedAt") LocalDateTime failedAt,
            @Param("processing") ReservationIdempotencyStatus processing,
            @Param("failedRetryable") ReservationIdempotencyStatus failedRetryable
    );
}

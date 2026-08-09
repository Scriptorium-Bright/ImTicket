package org.example.ticket.reservation.booking.repository;

import jakarta.persistence.LockModeType;
import org.example.ticket.reservation.booking.domain.ReservationIdempotency;
import org.example.ticket.reservation.booking.domain.ReservationIdempotencyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ReservationIdempotencyRepository extends JpaRepository<ReservationIdempotency, Long> {

    /**
     * 사용자와 멱등성 키 조합으로 기존 claim을 조회한다.
     * 동기 예약과 Queue Worker의 중복 요청 판정에 같은 조회 기준을 제공한다.
     */
    Optional<ReservationIdempotency> findByMemberIdAndIdempotencyKey(Long memberId, String idempotencyKey);

    /**
     * claim 상태를 변경하기 전에 대상 row를 배타적으로 조회한다.
     * 한 transaction만 현재 소유권과 상태를 판단하도록 보장한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ri from ReservationIdempotency ri where ri.id = :id")
    Optional<ReservationIdempotency> findByIdForUpdate(@Param("id") Long id);

    /**
     * 만료됐거나 재시도 가능한 claim을 새 attempt로 선점한다.
     * 요청 hash와 현재 상태 조건이 모두 맞을 때 한 행만 갱신한다.
     */
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
    int tryReclaim(
            @Param("id") Long id,
            @Param("requestHash") String requestHash,
            @Param("attemptToken") String attemptToken,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
            @Param("now") LocalDateTime now,
            @Param("processing") ReservationIdempotencyStatus processing,
            @Param("failedRetryable") ReservationIdempotencyStatus failedRetryable
    );

    /**
     * 현재 attempt가 소유한 PROCESSING claim을 재시도 가능 실패로 기록한다.
     * fencing token이 달라진 이전 실행의 상태 변경은 반영하지 않는다.
     */
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
    int markFailedIfOwned(
            @Param("id") Long id,
            @Param("attemptToken") String attemptToken,
            @Param("errorCode") String errorCode,
            @Param("failedAt") LocalDateTime failedAt,
            @Param("processing") ReservationIdempotencyStatus processing,
            @Param("failedRetryable") ReservationIdempotencyStatus failedRetryable
    );
}

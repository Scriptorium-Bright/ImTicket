package org.example.ticket.reservation.booking.repository;

import jakarta.persistence.LockModeType;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.util.constant.ReservationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    /**
     * 예약 상세 응답에 필요한 회원, 좌석과 공연 정보를 함께 조회한다.
     * 서비스가 지연 로딩에 의존하지 않고 상세 데이터를 사용할 수 있게 한다.
     */
    @Query("SELECT r " +
            "FROM Reservation r " +
            "JOIN FETCH r.member m " +
            "JOIN FETCH r.reservedSeats rs " +
            "JOIN FETCH rs.seat s " +
            "JOIN FETCH s.performanceTime pt " +
            "JOIN FETCH pt.performance p " +
            "WHERE r.id = :reservationId  ")
    Optional<Reservation> findByIdWithDetails(Long reservationId);

    /**
     * 단일 예약 row를 비관적 쓰기 lock으로 조회한다.
     * 결제와 만료가 같은 예약 상태를 동시에 바꾸는 상황을 직렬화한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :reservationId")
    Optional<Reservation> findByIdForUpdate(@Param("reservationId") Long reservationId);

    /**
     * 여러 예약 row를 ID 순서로 비관적 lock한다.
     * 만료 batch가 동일한 lock 순서를 사용해 교착 가능성을 줄인다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r " +
            "WHERE r.id IN :reservationIds " +
            "ORDER BY r.id")
    List<Reservation> findByIdInForUpdate(@Param("reservationIds") List<Long> reservationIds);

    /**
     * 예약 ID에 연결된 공연 정보를 조회한다.
     * 결제와 예약 후속 처리에서 공연 단위 정보를 확인할 때 사용한다.
     */
    @Query(
            "SELECT p " +
                    "FROM Reservation r " +
                    "JOIN r.reservedSeats rs " +
                    "JOIN rs.seat s " +
                    "JOIN s.performanceTime pt " +
                    "JOIN pt.performance p " +
                    "WHERE r.id = :reservationId"
    )
    Performance findByPerformance(Long reservationId);

    /**
     * 예약과 예약 좌석을 한 쿼리로 조회한다.
     * 좌석 상태를 함께 변경하는 서비스가 완전한 예약 구성을 얻는다.
     */
    @Query("SELECT r FROM Reservation r " +
            "JOIN FETCH r.reservedSeats rs " +
            "JOIN FETCH rs.seat s " +
            "WHERE r.id = :id")
    Optional<Reservation> findByIdWithSeats(@Param("id") Long id);

    /**
     * 기준 시각 이전에 만료된 대기 예약 ID를 오래된 순서로 조회한다.
     * Pageable 크기로 한 번의 정리 transaction 범위를 제한한다.
     */
    @Query("SELECT r.id FROM Reservation r " +
            "WHERE r.reservationStatus = :status " +
            "AND (r.expiredTime IS NULL OR r.expiredTime < :now) " +
            "ORDER BY r.expiredTime ASC")
    List<Long> findExpiredReservationIdsBefore(
            @Param("status") ReservationStatus status,
            @Param("now") LocalDateTime expiredTimeBefore,
            Pageable pageable
    );

}

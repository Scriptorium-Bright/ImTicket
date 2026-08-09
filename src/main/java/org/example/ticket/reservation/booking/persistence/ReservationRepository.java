package org.example.ticket.reservation.booking.persistence;

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


    @Query("SELECT r " +
            "FROM Reservation r " +
            "JOIN FETCH r.member m " +
            "JOIN FETCH r.reservedSeats rs " +
            "JOIN FETCH rs.seat s " +
            "JOIN FETCH s.performanceTime pt " +
            "JOIN FETCH pt.performance p " +
            "WHERE r.id = :reservationId  ")
    /** 예약 상세 화면에 필요한 연관 엔티티를 한 번에 조회한다. */
    Optional<Reservation> findByIdWithDetails(Long reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :reservationId")
    /** 단일 예약 row를 비관적 쓰기 lock으로 조회한다. */
    Optional<Reservation> findByIdForUpdate(@Param("reservationId") Long reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r " +
            "WHERE r.id IN :reservationIds " +
            "ORDER BY r.id")
    /** 여러 예약 row를 ID 순서로 비관적 lock해 deadlock 가능성을 낮춘다. */
    List<Reservation> findByIdInForUpdate(@Param("reservationIds") List<Long> reservationIds);

    @Query(
            "SELECT p " +
                    "FROM Reservation r " +
                    "JOIN r.reservedSeats rs " +
                    "JOIN rs.seat s " +
                    "JOIN s.performanceTime pt " +
                    "JOIN pt.performance p " +
                    "WHERE r.id = :reservationId"
    )
    /** 예약 ID에 연결된 공연 정보를 조회한다. */
    Performance findByPerformance(Long reservationId);

    @Query("SELECT r FROM Reservation r " +
            "JOIN FETCH r.reservedSeats rs " +
            "JOIN FETCH rs.seat s " +
            "WHERE r.id = :id")
    /** 예약과 예약 좌석을 함께 조회한다. */
    Optional<Reservation> findByIdWithSeats(@Param("id") Long id);

    @Query("SELECT r.id FROM Reservation r " +
            "WHERE r.reservationStatus = :status " +
            "AND (r.expiredTime IS NULL OR r.expiredTime < :now) " +
            "ORDER BY r.expiredTime ASC")
    /** 만료 대상 예약 ID를 정해진 batch 크기만큼 오래된 순서로 조회한다. */
    List<Long> findExpiredReservationIdsBefore(
            @Param("status") ReservationStatus status,
            @Param("now") LocalDateTime expiredTimeBefore,
            Pageable pageable
    );

}

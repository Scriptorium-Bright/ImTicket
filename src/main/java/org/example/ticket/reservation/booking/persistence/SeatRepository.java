package org.example.ticket.reservation.booking.persistence;

import jakarta.persistence.LockModeType;
import org.example.ticket.reservation.booking.api.SeatResponse;
import org.example.ticket.reservation.booking.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.QueryHint;
import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("""
            select s
            from Seat s
            where s.performanceTime.id = :performanceTimeId
              and s.id in :seatIds
            order by s.id
            """)
    /** 공연 회차의 좌석 row를 ID 순서로 비관적 lock해 선점 transaction에서 사용한다. */
    List<Seat> findByPerformanceTimeIdAndIdsForUpdate(
            @Param("performanceTimeId") Long performanceTimeId,
            @Param("seatIds") List<Long> seatIds
    );

    @Query("""
            select distinct s.id
            from ReservedSeat rs
            join rs.seat s
            where rs.reservation.id in :reservationIds
            order by s.id
            """)
    /** 예약 목록에 연결된 좌석 ID를 중복 없이 조회한다. */
    List<Long> findIdsByReservationIds(@Param("reservationIds") List<Long> reservationIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("""
            select s
            from Seat s
            where s.id in :seatIds
            order by s.id
            """)
    /** 공연 회차 조건 없이 좌석 row를 비관적 lock으로 조회한다. */
    List<Seat> findByIdsForUpdate(@Param("seatIds") List<Long> seatIds);

    @Query("""
            select s
            from Seat s
            where s.performanceTime.id = :performanceTimeId
              and s.id in :seatIds
            order by s.id
            """)
    /** 공연 회차에 속한 좌석을 lock 없이 조회한다. */
    List<Seat> findByPerformanceTimeIdAndIds(
            @Param("performanceTimeId") Long performanceTimeId,
            @Param("seatIds") List<Long> seatIds
    );

    @Query("SELECT new org.example.ticket.reservation.booking.api.SeatResponse(" +
            "s.id, s.seatFloor, s.seatSection, s.seatRow, s.seatNumber, s.seatType, s.price, s.isReservation, s.seatStatus) " +
            "FROM Seat s " +
            "WHERE s.performanceTime.id = :performanceTimeId")
    /** 좌석 배치도에 필요한 필드만 SeatResponse projection으로 조회한다. */
    List<SeatResponse> findSeatMapByPerformanceTimeId(@Param("performanceTimeId") Long performanceTimeId);

    /** 공연 회차의 모든 좌석 entity를 조회한다. */
    List<Seat> findAllByPerformanceTimeId(Long performanceTimeId);

}

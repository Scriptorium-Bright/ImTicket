package org.example.ticket.reservation.booking.repository;

import jakarta.persistence.LockModeType;
import org.example.ticket.reservation.booking.dto.response.SeatResponse;
import org.example.ticket.reservation.booking.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.QueryHint;
import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /**
     * 공연 회차에 속한 요청 좌석을 ID 순서로 비관적 lock한다.
     * 좌석 선점 transaction이 같은 순서로 잠금을 획득하게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("""
            select s
            from Seat s
            where s.performanceTime.id = :performanceTimeId
              and s.id in :seatIds
            order by s.id
            """)
    List<Seat> findByPerformanceTimeIdAndIdsForUpdate(
            @Param("performanceTimeId") Long performanceTimeId,
            @Param("seatIds") List<Long> seatIds
    );

    /**
     * 예약 목록에 연결된 좌석 ID를 중복 없이 조회한다.
     * 만료 서비스가 해제할 좌석 범위를 먼저 확정할 때 사용한다.
     */
    @Query("""
            select distinct s.id
            from ReservedSeat rs
            join rs.seat s
            where rs.reservation.id in :reservationIds
            order by s.id
            """)
    List<Long> findIdsByReservationIds(@Param("reservationIds") List<Long> reservationIds);

    /**
     * 좌석 ID 목록을 공연 회차 조건 없이 비관적 lock한다.
     * 이미 확정된 예약 목록의 좌석을 정리 transaction에서 잠글 때 사용한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("""
            select s
            from Seat s
            where s.id in :seatIds
            order by s.id
            """)
    List<Seat> findByIdsForUpdate(@Param("seatIds") List<Long> seatIds);

    /**
     * 공연 회차에 속한 요청 좌석을 잠금 없이 조회한다.
     * DB 비관적 잠금을 사용하지 않는 전략의 좌석 확인에 사용한다.
     */
    @Query("""
            select s
            from Seat s
            where s.performanceTime.id = :performanceTimeId
              and s.id in :seatIds
            order by s.id
            """)
    List<Seat> findByPerformanceTimeIdAndIds(
            @Param("performanceTimeId") Long performanceTimeId,
            @Param("seatIds") List<Long> seatIds
    );

    /**
     * 공연 회차의 좌석 배치도 필드를 응답 DTO로 직접 조회한다.
     * 전체 Seat entity 로딩 없이 조회 화면에 필요한 값만 반환한다.
     */
    @Query("SELECT new org.example.ticket.reservation.booking.dto.response.SeatResponse(" +
            "s.id, s.seatFloor, s.seatSection, s.seatRow, s.seatNumber, s.seatType, s.price, s.isReservation, s.seatStatus) " +
            "FROM Seat s " +
            "WHERE s.performanceTime.id = :performanceTimeId")
    List<SeatResponse> findSeatMapByPerformanceTimeId(@Param("performanceTimeId") Long performanceTimeId);

    /**
     * 공연 회차의 모든 좌석 entity를 조회한다.
     * 회차 단위 좌석 처리가 전체 좌석 상태를 필요로 할 때 사용한다.
     */
    List<Seat> findAllByPerformanceTimeId(Long performanceTimeId);

}

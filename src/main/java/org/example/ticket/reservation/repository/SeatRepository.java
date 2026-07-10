package org.example.ticket.reservation.repository;

import jakarta.persistence.LockModeType;
import org.example.ticket.reservation.response.SeatResponse;
import org.example.ticket.reservation.model.Seat;
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
    List<Seat> findByPerformanceTimeIdAndIdsForUpdate(
            @Param("performanceTimeId") Long performanceTimeId,
            @Param("seatIds") List<Long> seatIds
    );

    @Query("SELECT new org.example.ticket.reservation.response.SeatResponse(" +
            "s.id, s.seatFloor, s.seatSection, s.seatRow, s.seatNumber, s.seatType, s.price, s.isReservation, s.seatStatus) " +
            "FROM Seat s " +
            "WHERE s.performanceTime.id = :performanceTimeId")
    List<SeatResponse> findSeatMapByPerformanceTimeId(@Param("performanceTimeId") Long performanceTimeId);

    List<Seat> findAllByPerformanceTimeId(Long performanceTimeId);

}

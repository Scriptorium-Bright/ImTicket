package org.example.ticket.reservation.repository;

import io.lettuce.core.dynamic.annotation.Param;
import jakarta.persistence.LockModeType;
import org.example.ticket.reservation.response.SeatResponse;
import org.example.ticket.reservation.model.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {



    @Query("select s from Seat s where s.seatFloor = :floor and s.seatSection = :section and s.seatRow = :row and s.seatNumber = :seatNumber")
    Optional<Seat> findByIsReservationForUpdate(Integer floor, String section, Integer row, Integer seatNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id IN :id")
    List<Seat> findByIdsForUpdate(List<Long> id);

    @Query("select s from Seat s where s.id IN :id")
    List<Seat> findByIdsForUpdateWithDistribution(List<Long> id);

    @Lock(LockModeType.OPTIMISTIC)
    @Query("select s from Seat s where s.id IN :id")
    List<Seat> findByIdsForUpdateWithOptimistic(List<Long> id);

    @Query("select s.isReservation from Seat s where s.id = :id")
    Boolean findByIsReservation(Seat seat);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s JOIN FETCH s.performanceTime pt JOIN FETCH pt.performance WHERE s.id IN :seatIds")
    List<Seat> findBySeats(@Param("seatIds") List<Long> seatIds);

    @Query("SELECT new org.example.ticket.reservation.response.SeatResponse(" +
            "s.id, s.seatFloor, s.seatSection, s.seatRow, s.seatNumber, s.seatType, s.price, s.isReservation) " +
            "FROM Seat s " +
            "WHERE s.performanceTime.id = :performanceTimeId")
    List<SeatResponse> findByEmptySeat(@Param("performanceTimeId") Long performanceTimeId);

    List<Seat> findAllByPerformanceTimeId(Long performanceTimeId);

}

package org.example.ticket.reservation.repository;

import io.lettuce.core.dynamic.annotation.Param;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.reservation.model.Reservation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
    Optional<Reservation> findByIdWithDetails(Long reservationId);

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

    @Query("SELECT r FROM Reservation r " +
            "JOIN FETCH r.reservedSeats rs " +
            "JOIN FETCH rs.seat s " +
            "WHERE r.id = :id")
            Optional<Reservation> findByIdWithSeats(@Param("id") Long id);

    @Query("SELECT DISTINCT r FROM Reservation r " +
            "LEFT JOIN FETCH r.reservedSeats rs " +
            "LEFT JOIN FETCH rs.seat " +
            "WHERE r.expiredTime < :now")
    List<Reservation> findByExpiredTimeBefore(LocalDateTime expiredTimeBefore);

}

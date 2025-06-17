package org.example.ticket.reservation.repository;

import org.example.ticket.performance.model.Performance;
import org.example.ticket.reservation.model.Reservation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {


    @Query("SELECT m.walletAddress " +
            "FROM Reservation r " +
            "JOIN r.reservedSeats rs " +
            "JOIN rs.seat s " +
            "JOIN s.performanceTime pt " +
            "JOIN pt.performance p " +
            "JOIN p.organizer o " +
            "JOIN o.member m " +
            "WHERE r.id = :reservationId  ")
    String findByWalletAddressByOrganizer(Long reservationId);

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


}

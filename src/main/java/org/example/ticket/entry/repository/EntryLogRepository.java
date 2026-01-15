package org.example.ticket.entry.repository;

import org.example.ticket.entry.model.EntryLog;
import org.example.ticket.reservation.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EntryLogRepository extends JpaRepository<EntryLog, Long> {
    Optional<EntryLog> findByReservation(Reservation reservation);
    boolean existsByReservation(Reservation reservation);
}

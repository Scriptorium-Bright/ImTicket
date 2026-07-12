package org.example.ticket.member.repository;


import org.example.ticket.member.model.Organizer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrganizerRepository extends JpaRepository<Organizer, Long> {

    Optional<Organizer> findByMemberWalletAddressIgnoreCase(String walletAddress);
}

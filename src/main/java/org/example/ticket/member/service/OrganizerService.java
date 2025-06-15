package org.example.ticket.member.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.model.Organizer;
import org.example.ticket.member.repository.OrganizerRepository;
import org.example.ticket.member.request.OrganizerRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizerService {

    private final OrganizerRepository repository;

    public void registerOrganizer(OrganizerRequest request) {

        Organizer organizer = Organizer.builder()
                .walletAddress(request.getWalletAddress())
                .organizerName(request.getOrganizerName())
                .organizerType(request.getOrganizerType())
                .businessNumber(request.getBusinessNumber())
                .address(request.getAddress())
                .contactPhone(request.getContactPhone())
                .build();

        repository.save(
                organizer
        );
    }


}

package org.example.ticket.member.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.model.Organizer;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.member.repository.OrganizerRepository;
import org.example.ticket.member.request.OrganizerRequest;
import org.example.ticket.util.constant.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static org.example.ticket.util.constant.Role.ORGANIZER;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizerService {

    private final OrganizerRepository repository;
    private final MemberRepository memberRepository;

    @Transactional
    public void registerOrganizer(String walletAddress, OrganizerRequest request) {
        Member member = memberRepository.findByWalletAddress(walletAddress)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        Organizer organizer = initializeOrganizer(member, request);
        repository.save(organizer);

        changeRole(organizer);
    }


    private static Organizer initializeOrganizer(Member member, OrganizerRequest request) {
        return Organizer.builder()
                .organizerName(request.getOrganizerName())
                .organizerType(request.getOrganizerType())
                .businessNumber(request.getBusinessNumber())
                .address(request.getAddress())
                .contactEmail(request.getContactEmail())
                .member(member)
                .build();
    }

    private void changeRole(Organizer organizer) {
        organizer.getMember().changeMembersRole(ORGANIZER.getRole());
    }

}

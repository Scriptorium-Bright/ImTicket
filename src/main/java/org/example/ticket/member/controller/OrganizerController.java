package org.example.ticket.member.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.request.OrganizerRequest;
import org.example.ticket.member.service.OrganizerService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/organizer")
public class OrganizerController {

    private final OrganizerService organizerService;

    @PostMapping("/register")
    public ResponseEntity<?> registerOrganizerInformation(@AuthenticationPrincipal Member member, @RequestBody OrganizerRequest request) {
        organizerService.registerOrganizer(member, request);
        return ResponseEntity.ok().build();
    }


}

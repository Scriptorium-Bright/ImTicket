package org.example.ticket.member.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.request.RegisterRequest;
import org.example.ticket.member.service.AuthenticationService;
import org.example.ticket.member.service.MemberService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user")
public class MemberController {

    private final MemberService memberService;
    private final AuthenticationService authenticationService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {

        authenticationService.verifiedRegister(request);

        return ResponseEntity.ok(
                Collections.singletonMap("message", "Registration successful")
        );
    }

    @GetMapping("/validate/{walletAddress}")
    public ResponseEntity<?> validateMember(@PathVariable String walletAddress) {
        if (!memberService.existMemberWalletAddress(walletAddress)) {
            return ResponseEntity.badRequest().body("Invalid member");
        }
        return ResponseEntity.ok().build();
    }

    @PutMapping("/myPage/nickname")
    public ResponseEntity<String> changeNickname(@RequestParam("nickname") String nickname) {
        memberService.changeUsersNickname(nickname);
        return ResponseEntity.ok().body(nickname);
    }

    @GetMapping("/nickname")
    public ResponseEntity<String> fetchNickname(@RequestParam("walletAddress") String walletAddress) {
        String nickname = memberService.fetchUsersNickname(walletAddress);
        return ResponseEntity.ok(nickname);
    }

    @GetMapping("/walletAddress")
    public ResponseEntity<String> fetchWalletAddress(@RequestBody String nickname) {
        String walletAddress = memberService.fetchUsersWalletAddress(nickname);
        return ResponseEntity.ok(walletAddress);
    }

}

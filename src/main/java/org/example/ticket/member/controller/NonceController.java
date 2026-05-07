package org.example.ticket.member.controller;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.example.ticket.member.service.MemberService;
import org.example.ticket.util.ratelimit.BusinessRateLimitGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class NonceController {

    private final MemberService service;
    private final BusinessRateLimitGuard businessRateLimitGuard;

    @GetMapping("/api/user/nonce")
    public Map<String, Integer> getNonce(HttpServletRequest request, @RequestParam String walletAddress) {
        businessRateLimitGuard.checkNonceRequest(
                walletAddress,
                businessRateLimitGuard.resolveClientIp(request)
        );
        Integer userNonce = service.getOrCreateNonce(walletAddress);
        return Collections.singletonMap("nonce", userNonce);
    }
}

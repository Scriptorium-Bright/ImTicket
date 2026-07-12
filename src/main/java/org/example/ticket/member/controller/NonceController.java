package org.example.ticket.member.controller;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.example.ticket.member.model.NoncePurpose;
import org.example.ticket.member.response.NonceResponse;
import org.example.ticket.member.service.MemberService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.http.ResponseEntity;
import org.example.ticket.common.response.ApiResponse;

@RestController
@RequiredArgsConstructor
public class NonceController {

    private final MemberService service;

    @GetMapping("/api/user/nonce")
    public ResponseEntity<ApiResponse<NonceResponse>> getNonce(HttpServletRequest request,
                                                               @RequestParam String walletAddress,
                                                               @RequestParam(defaultValue = "LOGIN") NoncePurpose purpose) {
        NonceResponse nonce = service.getOrCreateNonce(walletAddress, purpose);
        return ResponseEntity.ok(ApiResponse.success(nonce));
    }
}

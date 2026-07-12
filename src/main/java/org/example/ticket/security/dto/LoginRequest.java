package org.example.ticket.security.dto;

public record LoginRequest(
        String walletAddress,
        String signature
) {
}

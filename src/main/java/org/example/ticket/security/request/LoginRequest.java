package org.example.ticket.security.request;

public record LoginRequest(
        String walletAddress,
        String signature
) {
}

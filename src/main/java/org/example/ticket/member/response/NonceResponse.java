package org.example.ticket.member.response;

import java.time.Instant;

public record NonceResponse(
        String nonce,
        String message,
        Instant expiresAt
) {
}

package org.example.ticket.security.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TokenResponse {
    private String token;
    private String walletAddress;
    private String role;
}

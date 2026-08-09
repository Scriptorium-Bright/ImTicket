package org.example.ticket.security.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TokenResponse {
    private String token;
    private String walletAddress;
    private String role;
}

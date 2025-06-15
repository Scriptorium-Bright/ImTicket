package org.example.ticket.member.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@AllArgsConstructor
@Builder
public class RegisterRequest {
    private String walletAddress;
    private String message;
    private String signature;
    private String phoneNumber;
    private String code;
    private String nickname;
}
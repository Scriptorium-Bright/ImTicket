package org.example.ticket.member.signature.request;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SignatureVerifyRequest {
    private String walletAddress;
    private String message;
    private String signature;
}

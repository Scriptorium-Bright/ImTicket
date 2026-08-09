package org.example.ticket.member.signature.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 서명 검증 컴포넌트에 전달하는 내부 검증 입력값이다. HTTP 요청 DTO가 아니다. */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SignatureVerification {

    private String walletAddress;
    private String message;
    private String signature;
}

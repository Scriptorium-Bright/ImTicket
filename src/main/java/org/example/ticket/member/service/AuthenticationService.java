package org.example.ticket.member.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.model.NoncePurpose;
import org.example.ticket.member.request.RegisterRequest;
import org.example.ticket.member.signature.dto.SignatureVerification;
import org.example.ticket.member.signature.SignatureVerifier;
import org.example.ticket.sms.service.SMSService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthenticationService {

    private final MemberService memberService;
    private final SignatureVerifier signatureVerifier;
    private final SMSService smsService;


    public void verifiedRegister(RegisterRequest request) {
        String walletAddress = request.getWalletAddress();
        String phoneNumber = request.getPhoneNumber();
        String nickname = request.getNickname();

        if (memberService.isRegisteredMember(walletAddress)) {
            throw new BadCredentialsException("Wallet address already registered.");
        }

        Member member = memberService.getMemberWithNonce(walletAddress);
        String expectedMessage = getExpectedRegisterMessage(member);
        if (!expectedMessage.equals(request.getMessage())) {
            throw new BadCredentialsException("Nonce challenge message mismatch.");
        }

        SignatureVerification verification = SignatureVerification
                .builder()
                .signature(request.getSignature())
                .walletAddress(request.getWalletAddress())
                .message(expectedMessage)
                .build();

        if (!signatureVerifier.verifySignature(verification)) {
            throw new BadCredentialsException("Wallet signature verification failed.");
        }

        // 2. SMS 인증 코드 검증
        String userInputCode = request.getCode();

        if (!smsService.verifiedCode(phoneNumber, userInputCode)) {
            throw new BadCredentialsException("SMS verification code is invalid.");
        }

        if (memberService.consumeNonce(walletAddress, member.getNonce(), NoncePurpose.REGISTER)) {
            throw new BadCredentialsException("Nonce challenge is expired or already used.");
        }

        memberService.register(walletAddress, phoneNumber, nickname);
    }

    private String getExpectedRegisterMessage(Member member) {
        try {
            return memberService.buildChallengeMessage(member, NoncePurpose.REGISTER);
        } catch (IllegalStateException e) {
            throw new BadCredentialsException(e.getMessage());
        }
    }
}

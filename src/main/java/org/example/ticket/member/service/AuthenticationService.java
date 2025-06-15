package org.example.ticket.member.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.request.RegisterRequest;
import org.example.ticket.member.signature.model.dto.SignatureVerifyRequest;
import org.example.ticket.member.signature.service.SignatureService;
import org.example.ticket.sms.service.SMSService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthenticationService {

    private final MemberService memberService;
    private final SignatureService signatureService;
    private final SMSService smsService;


    public void verifiedRegister(RegisterRequest request) {
        SignatureVerifyRequest sig = SignatureVerifyRequest
                .builder()
                .signature(request.getSignature())
                .walletAddress(request.getWalletAddress())
                .message(request.getMessage())
                .build();

        String walletAddress = request.getWalletAddress();
        String phoneNumber = request.getPhoneNumber();
        String nickname = request.getNickname();

        if (memberService.existMemberWalletAddress(walletAddress)) {
            throw new BadCredentialsException("Wallet address already registered.");
        }

        if (!signatureService.verifySignature(sig)) {
            throw new BadCredentialsException("Wallet signature verification failed.");
        }

        // 2. SMS 인증 코드 검증
        String userInputCode = request.getCode();
        if (!smsService.verifiedCode(phoneNumber, userInputCode)) {
            throw new BadCredentialsException("SMS verification code is invalid.");
        }

        memberService.register(walletAddress, phoneNumber, nickname);
    }

/*    public Map<?, ?> validateUserInfo() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null) {
            log.error("Authentication object is NULL in MemberController!");
            throw new BadCredentialsException("인증 실패 (Auth is null)");
        } else {
            log.info("Authentication object retrieved in MemberController: {}", authentication);
            log.info("Principal: {}", authentication.getPrincipal()); // Principal 객체 확인
            log.info("Authorities: {}", authentication.getAuthorities()); // 권한 확인
            log.info("Is Authenticated: {}", authentication.isAuthenticated());
        }

        String role = fetchUsersRole(authentication);
        String walletAddress = authentication.getName();


        log.info(walletAddress);

        if(!memberService.existMemberWalletAddress(walletAddress)) {
            throw new BadCredentialsException("not exist users wallet");
        }

        log.info("Verified walletAddress: {}", walletAddress);
        log.info("users role : {}", role);

        Map<String, String> jwt = provider.provideJwt(walletAddress, role);

        log.info("jwt : {}", jwt);
        if(jwt == null) {
            throw new BadCredentialsException("not exist jwt");
        }

        return jwt;
    }*/

/*    private static String fetchUsersRole(Authentication authentication) {
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        Iterator<? extends GrantedAuthority> iterator = authorities.iterator();
        GrantedAuthority next = iterator.next();

        return next.getAuthority();
    }*/
}
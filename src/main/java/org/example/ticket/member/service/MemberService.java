package org.example.ticket.member.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Optional;

import static org.example.ticket.util.constant.Role.USER;

@Service
@RequiredArgsConstructor
@Slf4j
public class MemberService {

    private final MemberRepository memberRepository;

    @Transactional
    public Integer getOrCreateNonce(String walletAddress) {
        Integer newNonce = createNonce();
        Optional<Member> memberOptional = memberRepository.findByWalletAddress(walletAddress);

        if (memberOptional.isPresent()) {
            Member member = memberOptional.get();
            member.updateNonce(newNonce);
            log.info("Updated nonce for existing user {}: {}", walletAddress, newNonce);
        } else {
            memberRepository.save(Member.builder()
                    .walletAddress(walletAddress)
                    .role(USER.getRole())
                    .nonce(newNonce)
                    .build());
            log.info("Generated and SAVED nonce for new user {}: {}", walletAddress, newNonce);
        }
        return newNonce;
    }

    public void register(String walletAddress, String phoneNumber, String nickname) {
        Integer nonce = createNonce();

        memberRepository.findByWalletAddress(walletAddress)
                .orElseGet(() -> memberRepository.save(
                        Member.builder()
                                .walletAddress(walletAddress)
                                .phoneNumber(phoneNumber)
                                .nickname(nickname)
                                .smsVerified(true)
                                .walletVerified(true)
                                .role(USER.getRole())
                                .nonce(nonce)
                                .build()));
    }

    public boolean existMemberWalletAddress(String walletAddress) {
        return memberRepository.existsMemberByWalletAddress(walletAddress);
    }

    @Transactional
    public void changeUsersNickname(String walletAddress, String nickname) {
        Member member = memberRepository.findByWalletAddress(walletAddress).orElseThrow();
        member.updateNickname(nickname);
    }

    public String fetchUsersNickname(String walletAddress) {
        return memberRepository.findNicknameByWalletAddress(walletAddress);
    }

    public String fetchUsersWalletAddress(String nickname) {
        return memberRepository.findWalletAddressByNickname(nickname);
    }

    public Integer createNonce() {
        SecureRandom secureRandom = new SecureRandom();
        return secureRandom.nextInt();
    }

}
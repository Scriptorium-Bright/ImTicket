package org.example.ticket.member.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Optional;

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
            log.info("Generated nonce for new/unfound user {}: {}", walletAddress, newNonce);
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
                                .role("ROLE_USER")
                                .nonce(nonce)
                                .build()));
    }

    public boolean existMemberWalletAddress(String walletAddress) {
        return memberRepository.existsMemberByWalletAddress(walletAddress);
    }

    @Transactional
    public void changeUsersNickname(String nickname) {
        Member member = Member
                .builder()
                .nickname(nickname)
                .build();
    }

    public String fetchUsersNickname(String walletAddress) {
        return memberRepository.findByNicknameWithWalletAddress(walletAddress);
    }

    public String fetchUsersWalletAddress(String nickname) {
        return memberRepository.findByWalletAddressWithNickname(nickname);
    }

    public Integer createNonce() {
        SecureRandom secureRandom = new SecureRandom();
        return secureRandom.nextInt();
    }


}
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
            log.info("Generated nonce for unregistered wallet {}: {}", walletAddress, newNonce);
        }
        return newNonce;
    }

    @Transactional
    public void register(String walletAddress, String phoneNumber, String nickname) {
        Integer nonce = createNonce();

        memberRepository.findByWalletAddress(walletAddress)
                .ifPresentOrElse(
                        member -> member.completeRegistration(phoneNumber, nickname, USER.getRole(), nonce),
                        () -> memberRepository.save(
                                Member.builder()
                                        .walletAddress(walletAddress)
                                        .phoneNumber(phoneNumber)
                                        .nickname(nickname)
                                        .smsVerified(true)
                                        .walletVerified(true)
                                        .role(USER.getRole())
                                        .nonce(nonce)
                                        .build()
                        )
                );
    }

    public boolean existMemberWalletAddress(String walletAddress) {
        return isRegisteredMember(walletAddress);
    }

    @Transactional
    public void changeUsersNickname(String walletAddress, String nickname) {
        Member member = getRegisteredMember(walletAddress);
        member.updateNickname(nickname);
    }

    public String fetchUsersNickname(String walletAddress) {
        return getRegisteredMember(walletAddress).getNickname();
    }

    public String fetchUsersWalletAddress(String nickname) {
        return memberRepository.findWalletAddressByNickname(nickname);
    }

    public boolean isRegisteredMember(String walletAddress) {
        return memberRepository.findByWalletAddress(walletAddress)
                .map(Member::isRegistered)
                .orElse(false);
    }

    @Transactional
    public void rotateNonce(String walletAddress) {
        Member member = getRegisteredMember(walletAddress);
        member.updateNonce(createNonce());
    }

    public Member getRegisteredMember(String walletAddress) {
        return memberRepository.findByWalletAddress(walletAddress)
                .filter(Member::isRegistered)
                .orElseThrow(() -> new IllegalArgumentException("등록된 사용자를 찾을 수 없습니다."));
    }

    public Integer createNonce() {
        SecureRandom secureRandom = new SecureRandom();
        return secureRandom.nextInt();
    }

}

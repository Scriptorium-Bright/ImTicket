package org.example.ticket.member.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.model.NoncePurpose;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.member.response.NonceResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.example.ticket.util.constant.Role.USER;

@Service
@RequiredArgsConstructor
@Slf4j
public class MemberService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Duration NONCE_TTL = Duration.ofMinutes(5);
    private static final Pattern WALLET_ADDRESS_PATTERN = Pattern.compile("^0x[a-fA-F0-9]{40}$");
    private static final String SIGNATURE_DOMAIN = "imticket.local";
    private static final String SIGNATURE_URI = "https://imticket.local";
    private static final String SIGNATURE_VERSION = "1";
    private static final String SIGNATURE_CHAIN_ID = "1";

    private final MemberRepository memberRepository;

    @Transactional
    public NonceResponse getOrCreateNonce(String walletAddress, NoncePurpose purpose) {
        validateWalletAddress(walletAddress);

        String normalizedWalletAddress = walletAddress.toLowerCase(Locale.ROOT);
        String newNonce = createNonce();
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(NONCE_TTL);
        NoncePurpose noncePurpose = purpose == null ? NoncePurpose.LOGIN : purpose;

        Member member = memberRepository.findByWalletAddressIgnoreCase(normalizedWalletAddress)
                .orElseGet(() -> memberRepository.save(Member.builder()
                        .walletAddress(normalizedWalletAddress)
                        .role(USER.getRole())
                        .smsVerified(false)
                        .walletVerified(false)
                        .build()));

        member.updateNonce(newNonce, noncePurpose, issuedAt, expiresAt);
        log.info("Issued {} nonce challenge for wallet {}", noncePurpose, normalizedWalletAddress);

        return new NonceResponse(
                newNonce,
                buildChallengeMessage(member.getWalletAddress(), newNonce, noncePurpose, issuedAt, expiresAt),
                expiresAt
        );
    }

    @Transactional
    public void register(String walletAddress, String phoneNumber, String nickname) {
        memberRepository.findByWalletAddressIgnoreCase(walletAddress)
                .ifPresentOrElse(
                        member -> member.completeRegistration(phoneNumber, nickname, USER.getRole()),
                        () -> memberRepository.save(
                                Member.builder()
                                        .walletAddress(walletAddress.toLowerCase(Locale.ROOT))
                                        .phoneNumber(phoneNumber)
                                        .nickname(nickname)
                                        .smsVerified(true)
                                        .walletVerified(true)
                                        .role(USER.getRole())
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
        return memberRepository.findByWalletAddressIgnoreCase(walletAddress)
                .map(Member::isRegistered)
                .orElse(false);
    }

    @Transactional
    public void rotateNonce(String walletAddress) {
        Member member = getRegisteredMember(walletAddress);
        Instant issuedAt = Instant.now();
        member.updateNonce(createNonce(), NoncePurpose.LOGIN, issuedAt, issuedAt.plus(NONCE_TTL));
    }

    public Member getRegisteredMember(String walletAddress) {
        return memberRepository.findByWalletAddressIgnoreCase(walletAddress)
                .filter(Member::isRegistered)
                .orElseThrow(() -> new IllegalArgumentException("등록된 사용자를 찾을 수 없습니다."));
    }

    public Member getMemberWithNonce(String walletAddress) {
        return memberRepository.findByWalletAddressIgnoreCase(walletAddress)
                .orElseThrow(() -> new IllegalArgumentException("Nonce challenge를 먼저 발급받아야 합니다."));
    }

    public String buildChallengeMessage(Member member, NoncePurpose purpose) {

        validateNonceState(member, purpose);

        return buildChallengeMessage(
                member.getWalletAddress(),
                member.getNonce(),
                purpose,
                member.getNonceIssuedAt(),
                member.getNonceExpiresAt()
        );
    }

    @Transactional
    public boolean consumeNonce(String walletAddress, String nonce, NoncePurpose purpose) {
        return memberRepository.consumeNonce(walletAddress, nonce, purpose, Instant.now()) != 1;
    }

    public String createNonce() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void validateWalletAddress(String walletAddress) {
        if (walletAddress == null || !WALLET_ADDRESS_PATTERN.matcher(walletAddress).matches()) {
            throw new IllegalArgumentException("Invalid wallet address format");
        }
    }

    private void validateNonceState(Member member, NoncePurpose purpose) {
        if (member.getNonce() == null || member.getNonceIssuedAt() == null || member.getNonceExpiresAt() == null) {
            throw new IllegalStateException("발급된 nonce challenge가 없습니다.");
        }
        if (member.getNoncePurpose() != purpose) {
            throw new IllegalStateException("Nonce challenge 목적이 일치하지 않습니다.");
        }
        if (!member.getNonceExpiresAt().isAfter(Instant.now())) {
            throw new IllegalStateException("Nonce challenge가 만료되었습니다.");
        }
    }

    private String buildChallengeMessage(String walletAddress, String nonce, NoncePurpose purpose,
                                         Instant issuedAt, Instant expiresAt) {
        return """
                %s wants you to sign in with your Ethereum account:
                %s

                Purpose: %s
                URI: %s
                Version: %s
                Chain ID: %s
                Nonce: %s
                Issued At: %s
                Expiration Time: %s
                """.formatted(
                SIGNATURE_DOMAIN,
                walletAddress,
                purpose.getDescription(),
                SIGNATURE_URI,
                SIGNATURE_VERSION,
                SIGNATURE_CHAIN_ID,
                nonce,
                issuedAt,
                expiresAt
        ).trim();
    }

}

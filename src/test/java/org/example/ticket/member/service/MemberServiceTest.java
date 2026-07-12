package org.example.ticket.member.service;

import org.example.ticket.member.model.Member;
import org.example.ticket.member.model.NoncePurpose;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.member.response.NonceResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    private static final String WALLET_ADDRESS = "0x0000000000000000000000000000000000000abc";

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private MemberService memberService;

    @Test
    void nonceRequestPersistsPendingChallengeForUnregisteredWallet() {
        when(memberRepository.findByWalletAddressIgnoreCase(WALLET_ADDRESS)).thenReturn(Optional.empty());
        when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NonceResponse response = memberService.getOrCreateNonce(WALLET_ADDRESS, NoncePurpose.REGISTER);

        assertNotNull(response.nonce());
        assertTrue(response.message().contains("Purpose: Register for ImTicket"));
        assertTrue(response.message().contains("Nonce: " + response.nonce()));
        verify(memberRepository).save(any(Member.class));
    }

    @Test
    void registerCompletesExistingPendingMember() {
        Member pendingMember = Member.builder()
                .walletAddress(WALLET_ADDRESS)
                .role("ROLE_USER")
                .build();
        when(memberRepository.findByWalletAddressIgnoreCase(WALLET_ADDRESS)).thenReturn(Optional.of(pendingMember));

        memberService.register(WALLET_ADDRESS, "01012345678", "tester");

        assertTrue(pendingMember.isRegistered());
        assertEquals("01012345678", pendingMember.getPhoneNumber());
        assertEquals("tester", pendingMember.getNickname());
    }

    @Test
    void rotateNonceUpdatesRegisteredMemberNonce() {
        Member registeredMember = Member.builder()
                .walletAddress(WALLET_ADDRESS)
                .phoneNumber("01012345678")
                .nickname("tester")
                .smsVerified(true)
                .walletVerified(true)
                .role("ROLE_USER")
                .nonce("old-nonce")
                .build();
        when(memberRepository.findByWalletAddressIgnoreCase(WALLET_ADDRESS)).thenReturn(Optional.of(registeredMember));

        memberService.rotateNonce(WALLET_ADDRESS);

        assertTrue(registeredMember.isRegistered());
        assertFalse(registeredMember.getNonce().equals("old-nonce"));
        assertEquals(NoncePurpose.LOGIN, registeredMember.getNoncePurpose());
    }
}

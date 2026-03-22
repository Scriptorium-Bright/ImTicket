package org.example.ticket.member.service;

import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private MemberService memberService;

    @Test
    void nonceRequestDoesNotPersistUnregisteredWallet() {
        when(memberRepository.findByWalletAddress("0xnew")).thenReturn(Optional.empty());

        Integer nonce = memberService.getOrCreateNonce("0xnew");

        assertNotEquals(null, nonce);
        verify(memberRepository, never()).save(any());
    }

    @Test
    void registerCompletesExistingPendingMember() {
        Member pendingMember = Member.builder()
                .walletAddress("0xabc")
                .role("ROLE_USER")
                .build();
        when(memberRepository.findByWalletAddress("0xabc")).thenReturn(Optional.of(pendingMember));

        memberService.register("0xabc", "01012345678", "tester");

        assertTrue(pendingMember.isRegistered());
        assertEquals("01012345678", pendingMember.getPhoneNumber());
        assertEquals("tester", pendingMember.getNickname());
        verify(memberRepository, never()).save(any());
    }

    @Test
    void rotateNonceUpdatesRegisteredMemberNonce() {
        Member registeredMember = Member.builder()
                .walletAddress("0xabc")
                .phoneNumber("01012345678")
                .nickname("tester")
                .smsVerified(true)
                .walletVerified(true)
                .role("ROLE_USER")
                .nonce(1)
                .build();
        when(memberRepository.findByWalletAddress("0xabc")).thenReturn(Optional.of(registeredMember));

        memberService.rotateNonce("0xabc");

        assertTrue(registeredMember.isRegistered());
        assertFalse(registeredMember.getNonce().equals(1));
    }
}

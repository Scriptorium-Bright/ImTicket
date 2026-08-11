package org.example.ticket.reservation.booking.service;

import jakarta.persistence.EntityNotFoundException;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.reservation.booking.dto.request.ReservationRequest;
import org.example.ticket.reservation.booking.dto.response.ReservationCreateResponse;
import org.example.ticket.reservation.booking.util.ReservationRequestHasher;
import org.example.ticket.reservation.common.value.ReservationIntentFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationPreReserveServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final String WALLET = "0xowner";
    private static final String KEY = "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1";

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private ReservationClaimExecutionService claimExecutionService;

    private final ReservationRequestHasher requestHasher = new ReservationRequestHasher();
    private ReservationPreReserveService preReserveService;

    @BeforeEach
    void setUp() {
        preReserveService = new ReservationPreReserveService(
                memberRepository,
                requestHasher,
                claimExecutionService
        );
    }

    @Test
    void normalizesHttpInputAndDelegatesToMemberIdExecution() {
        ReservationRequest request = new ReservationRequest(1L, List.of(2L, 1L));
        ReservationIntentFingerprint fingerprint = requestHasher.fingerprint(request);
        ReservationCreateResponse response = response();
        when(memberRepository.findIdByWalletAddressIgnoreCase(WALLET)).thenReturn(Optional.of(MEMBER_ID));
        when(claimExecutionService.execute(MEMBER_ID, KEY, request, fingerprint)).thenReturn(response);

        assertThat(preReserveService.preReserve(WALLET, KEY, request)).isSameAs(response);

        verify(claimExecutionService).execute(MEMBER_ID, KEY, request, fingerprint);
    }

    @Test
    void invalidRequestStopsBeforeMemberLookupAndClaimExecution() {
        ReservationRequest request = new ReservationRequest(null, List.of(1L));

        assertThatThrownBy(() -> preReserveService.preReserve(WALLET, KEY, request))
                .isInstanceOf(org.example.ticket.common.exception.BusinessException.class);

        verify(memberRepository, never()).findIdByWalletAddressIgnoreCase(WALLET);
        verify(claimExecutionService, never()).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void missingWalletMemberStopsBeforeClaimExecution() {
        ReservationRequest request = new ReservationRequest(1L, List.of(1L));
        when(memberRepository.findIdByWalletAddressIgnoreCase(WALLET)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> preReserveService.preReserve(WALLET, KEY, request))
                .isInstanceOf(EntityNotFoundException.class);

        verify(claimExecutionService, never()).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private ReservationCreateResponse response() {
        return ReservationCreateResponse.builder()
                .id(20L)
                .totalPrice(10000)
                .orderUid("reservation-20")
                .expiredTime(LocalDateTime.now().plusMinutes(7))
                .responses(List.of())
                .build();
    }
}

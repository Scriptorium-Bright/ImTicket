package org.example.ticket.reservation.booking.service;

import jakarta.persistence.EntityNotFoundException;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.reservation.booking.dto.request.ReservationRequest;
import org.example.ticket.reservation.booking.dto.response.ReservationCreateResponse;
import org.example.ticket.reservation.booking.util.ReservationRequestHasher;
import org.example.ticket.reservation.common.value.ReservationIntentFingerprint;
import org.springframework.stereotype.Service;

/** HTTP 예약 입력을 정규화하고 공통 DB claim 실행 경계로 전달한다. */
@Service
public class ReservationPreReserveService {

    private final MemberRepository memberRepository;
    private final ReservationRequestHasher requestHasher;
    private final ReservationClaimExecutionService claimExecutionService;

    /**
     * HTTP identity 조회와 요청 정규화에 필요한 의존성을 초기화한다.
     * DB claim 상태와 예약 실행 정책은 공통 실행 서비스에 위임한다.
     */
    public ReservationPreReserveService(
            MemberRepository memberRepository,
            ReservationRequestHasher requestHasher,
            ReservationClaimExecutionService claimExecutionService
    ) {
        this.memberRepository = memberRepository;
        this.requestHasher = requestHasher;
        this.claimExecutionService = claimExecutionService;
    }

    /**
     * 외부 멱등 키와 예약 요청을 정규화하고 wallet에 대응하는 회원 ID를 확인한다.
     * 검증된 입력을 동기 API와 Queue Worker가 공유하는 claim 실행 서비스로 전달한다.
     */
    public ReservationCreateResponse preReserve(
            String walletAddress,
            String rawIdempotencyKey,
            ReservationRequest request
    ) {
        String idempotencyKey = requestHasher.normalizeKey(rawIdempotencyKey);
        ReservationIntentFingerprint requestFingerprint = requestHasher.fingerprint(request);
        Long memberId = memberRepository.findIdByWalletAddressIgnoreCase(walletAddress)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        return claimExecutionService.execute(
                memberId,
                idempotencyKey,
                request,
                requestFingerprint
        );
    }
}

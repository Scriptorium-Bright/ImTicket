package org.example.ticket.reservation.booking.service;

import lombok.RequiredArgsConstructor;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.util.annotation.ReservationLock;
import org.example.ticket.reservation.booking.util.lock.ReservationLockStrategy;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.domain.ReservationIdempotency;
import org.example.ticket.reservation.booking.repository.ReservationIdempotencyRepository;
import org.example.ticket.reservation.booking.repository.ReservationRepository;
import org.example.ticket.reservation.booking.dto.request.ReservationRequest;
import org.example.ticket.reservation.booking.dto.response.ReservationCreateResponse;
import org.example.ticket.reservation.booking.util.ReservationResponseSnapshotCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationIdempotentCreationService {

    private final ReservationIdempotencyRepository idempotencyRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final ReservationResponseSnapshotCodec snapshotCodec;

    /**
     * 소유권이 확인된 멱등성 claim으로 예약을 생성하고, 성공 응답 snapshot까지 같은 트랜잭션에서 확정한다.
     * 설정된 좌석 잠금 전략을 적용해 동일 좌석의 동시 예약 생성을 직렬화한다.
     */
    @ReservationLock(strategy = ReservationLockStrategy.CONFIGURED)
    @Transactional
    public ReservationCreateResponse create(
            Long memberId,
            String walletAddress,
            ReservationRequest request,
            String requestHash,
            Long claimId,
            String attemptToken
    ) {
        ReservationIdempotency claim = idempotencyRepository.findByIdForUpdate(claimId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.IDEMPOTENCY_PROCESSING));
        if (!claim.isOwnedProcessingAttempt(memberId, requestHash, attemptToken)) {
            throw new BusinessException(ReservationErrorCode.IDEMPOTENCY_PROCESSING);
        }

        ReservationCreateResponse response = reservationService.createReservationWithinTransaction(
                walletAddress,
                request
        );
        String payload = snapshotCodec.encode(response);
        Reservation reservation = reservationRepository.getReferenceById(response.getId());
        claim.markSucceeded(
                reservation,
                ReservationResponseSnapshotCodec.CURRENT_SCHEMA_VERSION,
                payload
        );
        return response;
    }
}

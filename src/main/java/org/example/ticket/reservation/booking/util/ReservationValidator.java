package org.example.ticket.reservation.booking.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.reservation.booking.dto.request.ReservationRequest;
import org.example.ticket.util.constant.SeatStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.example.ticket.util.constant.ReservationStatus.PENDING_PAYMENT;
import static org.example.ticket.util.constant.SeatStatus.LOCKED;
import static org.example.ticket.util.constant.SeatStatus.RESERVED;
import static org.example.ticket.util.constant.SeatStatus.UNAVAILABLE;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ReservationValidator {

    private static final Set<SeatStatus> UNAVAILABLE_SEAT_STATUSES = Set.of(RESERVED, UNAVAILABLE, LOCKED);

    /**
     * 예약 생성 요청에 공연 회차와 좌석이 포함됐는지 검증한다.
     * 필수 입력 누락을 좌석 조회와 transaction 시작 전에 거절한다.
     */
    public static void validateCreateRequest(ReservationRequest request) {
        if (request.getPerformanceTimeId() == null) {
            throw new BusinessException(ReservationErrorCode.PERFORMANCE_TIME_REQUIRED);
        }
        if (request.getSeatIds() == null || request.getSeatIds().isEmpty()) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_SEAT_REQUIRED);
        }
    }

    /**
     * 원본 좌석 목록과 정규화 목록의 크기를 비교한다.
     * 같은 좌석이 반복된 예약 요청을 명시적 충돌 오류로 거절한다.
     */
    public static void validateNoDuplicateSeatIds(List<Long> originalSeatIds, List<Long> normalizedSeatIds) {
        if (normalizedSeatIds.size() != originalSeatIds.size()) {
            throw new BusinessException(ReservationErrorCode.DUPLICATE_SEAT_INCLUDED);
        }
    }

    /**
     * 현재 시각을 기준으로 예약 소유자, 상태와 만료 시각을 검증한다.
     * 결제 완료 서비스가 사용하는 기본 시간 경계를 제공한다.
     */
    public static void validateConfirmable(Reservation reservation, String walletAddress) {
        validateConfirmable(reservation, walletAddress, LocalDateTime.now());
    }

    /**
     * 지정한 시각을 기준으로 예약 소유자, 상태와 만료 여부를 검증한다.
     * 테스트와 결제 transaction이 동일한 확인 규칙을 사용하게 한다.
     */
    public static void validateConfirmable(
            Reservation reservation,
            String walletAddress,
            LocalDateTime now
    ) {
        if (!isOwner(reservation, walletAddress)) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_NOT_OWNER);
        }
        if (reservation.getReservationStatus() != PENDING_PAYMENT) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_NOT_PENDING);
        }
        if (reservation.getExpiredTime() == null || reservation.getExpiredTime().isBefore(now)) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_EXPIRED);
        }
    }

    /**
     * 요청 좌석에 예약 완료, 사용 불가 또는 잠금 상태가 있는지 확인한다.
     * 사용할 수 없는 좌석이 하나라도 있으면 예약 생성을 중단한다.
     */
    public static void validateSeatsAvailable(List<Seat> seats) {
        boolean isReserved = seats.stream()
                .anyMatch(seat -> UNAVAILABLE_SEAT_STATUSES.contains(seat.getSeatStatus()));

        if (isReserved) {
            throw new BusinessException(ReservationErrorCode.SEAT_ALREADY_RESERVED);
        }
    }

    /**
     * 예약 소유자와 요청자의 wallet 주소가 같은지 확인한다.
     * 현재 회원 조회 규칙에 맞춰 대소문자를 구분하지 않는다.
     */
    private static boolean isOwner(Reservation reservation, String walletAddress) {
        if (reservation.getMember() == null) {
            return false;
        }
        String ownerWalletAddress = reservation.getMember().getWalletAddress();
        return ownerWalletAddress != null
                && walletAddress != null
                && ownerWalletAddress.equalsIgnoreCase(walletAddress);
    }
}

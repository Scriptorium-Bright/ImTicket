package org.example.ticket.reservation.validation;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.exception.ReservationErrorCode;
import org.example.ticket.reservation.model.Reservation;
import org.example.ticket.reservation.model.Seat;
import org.example.ticket.reservation.request.ReservationRequest;
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

    /** 예약 생성 요청에 공연 회차와 좌석이 포함됐는지 검증한다. */
    public static void validateCreateRequest(ReservationRequest request) {
        if (request.getPerformanceTimeId() == null) {
            throw new BusinessException(ReservationErrorCode.PERFORMANCE_TIME_REQUIRED);
        }
        if (request.getSeatIds() == null || request.getSeatIds().isEmpty()) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_SEAT_REQUIRED);
        }
    }

    /** 원본 좌석 목록과 정규화 목록을 비교해 중복 좌석 요청을 거절한다. */
    public static void validateNoDuplicateSeatIds(List<Long> originalSeatIds, List<Long> normalizedSeatIds) {
        if (normalizedSeatIds.size() != originalSeatIds.size()) {
            throw new BusinessException(ReservationErrorCode.DUPLICATE_SEAT_INCLUDED);
        }
    }

    /** 현재 시각을 기준으로 예약 소유자·결제 대기 상태·만료 시각을 검증한다. */
    public static void validateConfirmable(Reservation reservation, String walletAddress) {
        validateConfirmable(reservation, walletAddress, LocalDateTime.now());
    }

    /** 지정한 시각을 기준으로 예약 소유자·상태·결제 가능 만료 여부를 검증한다. */
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

    /** 요청한 좌석 중 이미 예약·사용 불가·잠금 상태인 좌석이 없는지 검증한다. */
    public static void validateSeatsAvailable(List<Seat> seats) {
        boolean isReserved = seats.stream()
                .anyMatch(seat -> UNAVAILABLE_SEAT_STATUSES.contains(seat.getSeatStatus()));

        if (isReserved) {
            throw new BusinessException(ReservationErrorCode.SEAT_ALREADY_RESERVED);
        }
    }

    /** 예약 소유자의 wallet address와 요청자의 address가 일치하는지 확인한다. */
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

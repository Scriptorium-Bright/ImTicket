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

    public static void validateCreateRequest(ReservationRequest request) {
        if (request.getPerformanceTimeId() == null) {
            throw new BusinessException(ReservationErrorCode.PERFORMANCE_TIME_REQUIRED);
        }
        if (request.getSeatIds() == null || request.getSeatIds().isEmpty()) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_SEAT_REQUIRED);
        }
    }

    public static void validateNoDuplicateSeatIds(List<Long> originalSeatIds, List<Long> normalizedSeatIds) {
        if (normalizedSeatIds.size() != originalSeatIds.size()) {
            throw new BusinessException(ReservationErrorCode.DUPLICATE_SEAT_INCLUDED);
        }
    }

    public static void validateConfirmable(Reservation reservation, String walletAddress) {
        if (!isOwner(reservation, walletAddress)) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_NOT_OWNER);
        }
        if (reservation.getReservationStatus() != PENDING_PAYMENT) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_NOT_PENDING);
        }
        if (reservation.getExpiredTime() == null || reservation.getExpiredTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_EXPIRED);
        }
    }

    public static void validateSeatsAvailable(List<Seat> seats) {
        boolean isReserved = seats.stream()
                .anyMatch(seat -> UNAVAILABLE_SEAT_STATUSES.contains(seat.getSeatStatus()));

        if (isReserved) {
            throw new BusinessException(ReservationErrorCode.SEAT_ALREADY_RESERVED);
        }
    }

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

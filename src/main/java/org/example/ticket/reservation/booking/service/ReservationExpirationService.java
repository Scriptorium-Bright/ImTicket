package org.example.ticket.reservation.booking.service;

import lombok.RequiredArgsConstructor;
import org.example.ticket.reservation.booking.dto.ReservationExpirationResult;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.reservation.booking.repository.ReservationRepository;
import org.example.ticket.reservation.booking.repository.SeatRepository;
import org.example.ticket.util.constant.ReservationStatus;
import org.example.ticket.util.constant.SeatStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReservationExpirationService {

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;

    /**
     * 지정 시각 이전에 만료된 결제 대기 예약을 한 배치만큼 정리하고 연결 좌석을 다시 예약 가능 상태로 되돌린다.
     * 후보를 다시 행 잠금으로 조회해 만료 여부를 재검증하므로, 결제 완료와 정리 작업이 경합해도 최신 상태만 변경한다.
     */
    @Transactional
    public ReservationExpirationResult expireReservations(LocalDateTime now, int batchSize) {
        List<Long> candidateIds = reservationRepository.findExpiredReservationIdsBefore(
                ReservationStatus.PENDING_PAYMENT,
                now,
                PageRequest.of(0, batchSize)
        );
        if (candidateIds.isEmpty()) {
            return ReservationExpirationResult.empty();
        }

        List<Long> orderedCandidateIds = candidateIds.stream().distinct().sorted().toList();
        List<Reservation> lockedReservations = reservationRepository.findByIdInForUpdate(orderedCandidateIds);
        List<Reservation> expiredReservations = lockedReservations.stream()
                .filter(reservation -> isExpiredPendingReservation(reservation, now))
                .toList();
        if (expiredReservations.isEmpty()) {
            return ReservationExpirationResult.empty();
        }

        List<Long> expiredReservationIds = expiredReservations.stream()
                .map(Reservation::getId)
                .sorted()
                .toList();
        List<Long> seatIds = seatRepository.findIdsByReservationIds(expiredReservationIds);
        List<Seat> lockedSeats = seatIds.isEmpty()
                ? List.of()
                : seatRepository.findByIdsForUpdate(seatIds);

        expiredReservations.forEach(Reservation::expire);
        lockedSeats.forEach(seat -> seat.markAsReserved(SeatStatus.AVAILABLE));

        return new ReservationExpirationResult(expiredReservations.size(), lockedSeats.size());
    }

    /**
     * row lock 획득 후에도 예약이 만료된 결제 대기 상태인지 다시 확인한다.
     * 먼저 완료된 결제 결과를 만료 batch가 덮어쓰지 않게 한다.
     */
    private boolean isExpiredPendingReservation(Reservation reservation, LocalDateTime now) {
        return reservation.getReservationStatus() == ReservationStatus.PENDING_PAYMENT
                && (reservation.getExpiredTime() == null || reservation.getExpiredTime().isBefore(now));
    }

}

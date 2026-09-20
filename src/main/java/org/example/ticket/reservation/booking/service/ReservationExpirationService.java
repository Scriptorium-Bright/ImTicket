package org.example.ticket.reservation.booking.service;

import lombok.RequiredArgsConstructor;
import org.example.ticket.lifecycle.event.LifecycleActorType;
import org.example.ticket.lifecycle.event.LifecycleEntityType;
import org.example.ticket.lifecycle.event.LifecycleEventDraft;
import org.example.ticket.lifecycle.event.LifecycleEventPayload;
import org.example.ticket.lifecycle.event.LifecycleEventType;
import org.example.ticket.lifecycle.event.LifecycleEventWriter;
import org.example.ticket.lifecycle.event.LifecycleStateChange;
import org.example.ticket.reservation.booking.cache.SeatMapInvalidationPublisher;
import org.example.ticket.reservation.booking.dto.ReservationExpirationResult;
import org.example.ticket.reservation.booking.dto.ReservationSeatReference;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReservationExpirationService {

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final SeatMapInvalidationPublisher seatMapInvalidationPublisher;
    private final LifecycleEventWriter lifecycleEventWriter;

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
        List<ReservationSeatReference> seatReferences = seatRepository
                .findReservationSeatReferencesByReservationIds(expiredReservationIds);
        Map<Long, List<Long>> seatIdsByReservation = seatIdsByReservation(seatReferences);
        List<Long> seatIds = seatReferences.stream()
                .map(ReservationSeatReference::seatId)
                .distinct()
                .sorted()
                .toList();
        List<Seat> lockedSeats = seatIds.isEmpty()
                ? List.of()
                : seatRepository.findByIdsForUpdate(seatIds);

        expiredReservations.forEach(Reservation::expire);
        lockedSeats.forEach(seat -> seat.markAsReserved(SeatStatus.AVAILABLE));
        seatMapInvalidationPublisher.publishForSeats(lockedSeats);
        expiredReservations.forEach(reservation -> lifecycleEventWriter.recordDecision(
                reservation,
                List.of(expirationEvent(reservation, seatIdsByReservation.getOrDefault(reservation.getId(), List.of())))
        ));

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

    /**
     * 한 배치에서 잠근 좌석을 예약별 사건 payload로 다시 묶는다.
     * 좌석 행을 한 번만 잠그면서 각 예약의 좌석 목록을 보존한다.
     */
    private Map<Long, List<Long>> seatIdsByReservation(List<ReservationSeatReference> seatReferences) {
        Map<Long, List<Long>> grouped = new LinkedHashMap<>();
        seatReferences.forEach(reference -> grouped
                .computeIfAbsent(reference.reservationId(), ignored -> new ArrayList<>())
                .add(reference.seatId()));
        return grouped;
    }

    /**
     * 스케줄러가 결제 대기 예약을 만료시킨 결과를 단일 사건으로 표현한다.
     * 예약별 writer 호출이 독립된 Lifecycle 순번을 증가시킨다.
     */
    private LifecycleEventDraft expirationEvent(Reservation reservation, List<Long> seatIds) {
        List<LifecycleStateChange> stateChanges = new ArrayList<>();
        stateChanges.add(LifecycleStateChange.changed(
                LifecycleEntityType.RESERVATION,
                reservation.getId(),
                ReservationStatus.PENDING_PAYMENT.name(),
                ReservationStatus.EXPIRED.name()
        ));
        seatIds.forEach(seatId -> stateChanges.add(LifecycleStateChange.changed(
                LifecycleEntityType.SEAT,
                seatId,
                SeatStatus.LOCKED.name(),
                SeatStatus.AVAILABLE.name()
        )));
        return new LifecycleEventDraft(
                LifecycleEventType.RESERVATION_EXPIRED,
                LifecycleActorType.EXPIRATION_SCHEDULER,
                null,
                null,
                new LifecycleEventPayload(seatIds, stateChanges, "RESERVATION_EXPIRED_BY_SCHEDULER")
        );
    }

}

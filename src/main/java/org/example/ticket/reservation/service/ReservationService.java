package org.example.ticket.reservation.service;


import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.reservation.response.ReservationCreateResponse;
import org.example.ticket.reservation.model.Reservation;
import org.example.ticket.reservation.model.ReservedSeat;
import org.example.ticket.reservation.model.Seat;
import org.example.ticket.reservation.request.ReservationRequest;
import org.example.ticket.reservation.repository.ReservationRepository;
import org.example.ticket.reservation.validation.ReservationValidator;
import org.example.ticket.util.tracing.TracingConstants;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.example.ticket.util.constant.ReservationStatus.PENDING_PAYMENT;
import static org.example.ticket.util.constant.SeatStatus.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final MemberRepository memberRepository;
    private final SeatService seatService;
    private final static long EXPIRED_SCHEDULING_TIME = 30000;
    private static final int EXPIRED_CLEANUP_BATCH_SIZE = 5000;


    @Scheduled(fixedDelay = EXPIRED_SCHEDULING_TIME)
    @SchedulerLock(name = "cleanupExpiredReservation", lockAtMostFor = "PT6M")
    @Transactional
    public void cleanupExpiredReservation() {
        String runId = UUID.randomUUID().toString();
        String correlationId = "cleanup:" + runId;
        int reservationCount = 0;
        int seatCount = 0;

        MDC.put(TracingConstants.RUN_ID_MDC_KEY, runId);
        MDC.put(TracingConstants.CORRELATION_ID_MDC_KEY, correlationId);

        log.info("Starting expired reservation cleanup. batchSize={}", EXPIRED_CLEANUP_BATCH_SIZE);

        try {
            LocalDateTime now = LocalDateTime.now();

            List<Long> expiredReservationIds = reservationRepository.findExpiredReservationIdsBefore(
                    PENDING_PAYMENT,
                    now,
                    PageRequest.of(0, EXPIRED_CLEANUP_BATCH_SIZE)
            );

            if (expiredReservationIds.isEmpty()) {
                log.info("No expired reservations found. reservationCount=0, seatCount=0");
                return;
            }

            List<Reservation> byExpiredTimeBefore = reservationRepository.findByIdInWithSeats(expiredReservationIds);
            reservationCount = byExpiredTimeBefore.size();

            List<Seat> seats = byExpiredTimeBefore.stream()
                    .flatMap(reservation -> reservation.getReservedSeats().stream())
                    .map(ReservedSeat::getSeat)
                    .toList();
            seatCount = seats.size();

            seatService.changeSeatsState(seats, AVAILABLE);

            reservationRepository.deleteAll(byExpiredTimeBefore);
            log.info("Completed expired reservation cleanup. reservationCount={}, seatCount={}",
                    reservationCount,
                    seatCount);
        } catch (Exception e) {
            log.error("Failed expired reservation cleanup. reservationCount={}, seatCount={}",
                    reservationCount,
                    seatCount,
                    e);
            throw e;
        } finally {
            MDC.clear();
        }
    }

    @Transactional
    public ReservationCreateResponse createReservation(String walletAddress, ReservationRequest request) {

        ReservationValidator.validateCreateRequest(request);

        List<Long> seatIds = request.getSeatIds()
                .stream()
                .distinct()
                .sorted()
                .toList();

        ReservationValidator.validateNoDuplicateSeatIds(request.getSeatIds(), seatIds);

        Member member = memberRepository.findByWalletAddressIgnoreCase(walletAddress)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        List<Seat> seats = seatService.findAndLockSeatsByPerformanceTime(request.getPerformanceTimeId(), seatIds);
        checkSeatsAvailability(seats);

        seatService.changeSeatsState(seats, LOCKED);

        int totalPrice = seats.stream()
                .mapToInt(Seat::getPrice)
                .sum();

        String reservationCode = makeReservationCode();

        Reservation reservation = Reservation.builder()
                .totalPrice(totalPrice)
                .member(member)
                .reservationCode(reservationCode)
                .expiredTime(LocalDateTime.now().plusMinutes(7L))
                .reservationStatus(PENDING_PAYMENT)
                .build();

        List<ReservedSeat> reservedSeats = seats.stream()
                .map(seat -> ReservedSeat.builder()
                        .reservation(reservation)
                        .seat(seat)
                        .build())
                .toList();

        reservation.setReservedSeats(reservedSeats);
        reservationRepository.save(reservation);

        return ReservationCreateResponse.from(reservation);

    }

    private String makeReservationCode() {
        return UUID.randomUUID().toString();
    }

    public void checkSeatsAvailability(List<Seat> seats) {

        ReservationValidator.validateSeatsAvailable(seats);

    }


}

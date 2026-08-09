package org.example.ticket.reservation.booking.service;


import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.reservation.booking.dto.ReservationExpirationResult;
import org.example.ticket.reservation.booking.dto.response.ReservationCreateResponse;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.domain.ReservedSeat;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.reservation.booking.dto.request.ReservationRequest;
import org.example.ticket.reservation.booking.repository.ReservationRepository;
import org.example.ticket.reservation.booking.util.annotation.ReservationLock;
import org.example.ticket.reservation.booking.util.lock.ReservationLockStrategy;
import org.example.ticket.reservation.booking.util.ReservationValidator;
import org.example.ticket.util.tracing.TracingConstants;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

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
    private final ReservationExpirationService reservationExpirationService;
    private final static long EXPIRED_SCHEDULING_TIME = 30000;
    private static final int EXPIRED_CLEANUP_BATCH_SIZE = 5000;

    /**
     * 만료 시각이 지난 결제 대기 예약을 주기적으로 정리하고 좌석을 다시 예약 가능 상태로 돌린다.
     * ShedLock으로 여러 인스턴스가 있어도 한 번에 한 실행자만 정리하며, 실행 단위의 추적 ID를 MDC에 남긴다.
     */
    @Scheduled(fixedDelay = EXPIRED_SCHEDULING_TIME)
    @SchedulerLock(name = "cleanupExpiredReservation", lockAtMostFor = "PT6M")
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

            ReservationExpirationResult result =
                    reservationExpirationService.expireReservations(now, EXPIRED_CLEANUP_BATCH_SIZE);
            reservationCount = result.reservationCount();
            seatCount = result.seatCount();

            if (reservationCount == 0) {
                log.info("No expired reservations found. reservationCount=0, seatCount=0");
                return;
            }
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

    /**
     * 일반 동기 예매 요청의 진입점이다.
     * 좌석별 lock 전략을 적용한 뒤 하나의 트랜잭션에서 좌석 선점과 예약 생성을 완료한다.
     */
    @ReservationLock(strategy = ReservationLockStrategy.CONFIGURED)
    @Transactional
    public ReservationCreateResponse createReservation(String walletAddress, ReservationRequest request) {
        return createReservationCore(walletAddress, request);
    }

    /**
     * 이미 시작된 트랜잭션 안에서 예매 생성 규칙만 실행한다.
     * 멱등성 처리나 비동기 worker처럼 호출자가 트랜잭션 경계를 소유할 때 사용하며, 독립 트랜잭션 생성을 허용하지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationCreateResponse createReservationWithinTransaction(
            String walletAddress,
            ReservationRequest request
    ) {
        return createReservationCore(walletAddress, request);
    }

    /**
     * 요청 검증부터 좌석 잠금, 상태 변경과 예약 저장까지의 공통 예매 생성 흐름을 수행한다.
     * 호출자가 보장한 lock 및 트랜잭션 경계 안에서만 실행하며, 성공하면 좌석은 {@code LOCKED}, 예약은 {@code PENDING_PAYMENT} 상태가 된다.
     */
    private ReservationCreateResponse createReservationCore(
            String walletAddress,
            ReservationRequest request
    ) {

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

    /**
     * 결제 대기 예약과 연결할 UUID 기반 주문 식별자를 만든다.
     * 예약 생성마다 충돌 가능성이 낮은 독립 코드를 반환한다.
     */
    private String makeReservationCode() {
        return UUID.randomUUID().toString();
    }

    /**
     * 조회하거나 잠근 좌석 목록의 예약 가능 상태를 검증한다.
     * 하나라도 사용할 수 없으면 예약 transaction을 예외로 중단한다.
     */
    public void checkSeatsAvailability(List<Seat> seats) {

        ReservationValidator.validateSeatsAvailable(seats);

    }


}

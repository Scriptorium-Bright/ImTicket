package org.example.ticket.reservation.service;


import com.siot.IamportRestClient.exception.IamportResponseException;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.reservation.request.ReservationCheckRequest;
import org.example.ticket.reservation.response.ReservationCreateResponse;
import org.example.ticket.reservation.model.Reservation;
import org.example.ticket.reservation.model.ReservedSeat;
import org.example.ticket.reservation.model.Seat;
import org.example.ticket.reservation.request.ReservationRequest;
import org.example.ticket.reservation.repository.ReservationRepository;
import org.example.ticket.reservation.response.ReservationSuccessResponse;
import org.example.ticket.util.constant.ReservationStatus;
import org.example.ticket.util.constant.SeatStatus;
//import org.redisson.api.RLock;
//import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.example.ticket.util.constant.ReservationStatus.PENDING_PAYMENT;
import static org.example.ticket.util.constant.ReservationStatus.SUCCESS;
import static org.example.ticket.util.constant.SeatStatus.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final MemberRepository memberRepository;
    private final SeatService seatService;
    private final static long EXPIRED_SCHEDULING_TIME = 420000;


    @Transactional
    public ReservationSuccessResponse confirmReservation(ReservationCheckRequest request) {

        Reservation reservation = reservationRepository.findByIdWithDetails(request.getReservationId())
                .orElseThrow(() -> new EntityNotFoundException("해당 예약을 찾을 수 없습니다."));

        List<Seat> seats = reservation.getReservedSeats().stream().map(ReservedSeat::getSeat).toList();


        if(reservation.getReservationStatus() != PENDING_PAYMENT) {
            throw new RuntimeException("예약 대기 상태가 아닙니다."); // custom Exception
        }

        if(reservation.getExpiredTime().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("이미 만료된 좌석입니다. 처음부터 다시 진행해야합니다."); // custom Exception
        }

        reservation.setReservationStatus(SUCCESS);
        reservation.setExpiredTime(null);

        Performance performance = reservation.getReservedSeats().getFirst().getSeat().getPerformanceTime().getPerformance();
        String walletAddress = reservation.getMember().getWalletAddress();

        seatService.changeSeatsState(seats, RESERVED);

        return ReservationSuccessResponse.from(reservation, performance, walletAddress);
    }

    @Scheduled(fixedRate = EXPIRED_SCHEDULING_TIME)
    @Transactional
    public void cleanupExpiredReservation() {
        LocalDateTime now = LocalDateTime.now();

        List<Reservation> byExpiredTimeBefore = reservationRepository.findByExpiredTimeBefore(now);

        List<Seat> seats = byExpiredTimeBefore.stream()
                .flatMap(reservation -> reservation.getReservedSeats().stream())
                .map(ReservedSeat::getSeat)
                .toList();

        seatService.changeSeatsState(seats, AVAILABLE);

        reservationRepository.deleteAll(byExpiredTimeBefore);
    }

    @Transactional
    public ReservationCreateResponse createReservation(String walletAddress, ReservationRequest request) {
        Member member = memberRepository.findByWalletAddress(walletAddress)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        List<Seat> seats = seatService.findAndLockSeatsByIds(request.getSeatIds());
        checkSeatsAvailability(seats);

        seatService.changeSeatsState(seats, LOCKED);

        int totalPrice = seats.stream().mapToInt(Seat::getPrice).sum();

        String reservationCode = member.makeReservationCode();

        Reservation reservation = Reservation.builder()
                .totalPrice(totalPrice)
                .member(member)
                .reservationCode(reservationCode)
                .expiredTime(LocalDateTime.now().plusMinutes(7L))
                .reservationStatus(PENDING_PAYMENT)
                .build();

        // ----

        List<ReservedSeat> reservedSeats = seats.stream()
                .map(seat -> ReservedSeat.builder().reservation(reservation).seat(seat).build())
                .toList();

        reservation.setReservedSeats(reservedSeats);

        reservationRepository.save(reservation);

        return ReservationCreateResponse.from(reservation);

    }

    @Transactional
    public ReservationCreateResponse createReservationWithDistribution(String walletAddress, ReservationRequest request) {
        Member member = memberRepository.findByWalletAddress(walletAddress)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        List<Seat> seats = seatService.findAndLockSeatsByIdsWithDistribution(request.getSeatIds());
        checkSeatsAvailability(seats);

        seatService.changeSeatsState(seats, LOCKED);

        int totalPrice = seats.stream().mapToInt(Seat::getPrice).sum();

        String reservationCode = member.makeReservationCode();

        Reservation reservation = Reservation.builder()
                .totalPrice(totalPrice)
                .member(member)
                .reservationCode(reservationCode)
                .expiredTime(LocalDateTime.now().plusMinutes(7L))
                .reservationStatus(PENDING_PAYMENT)
                .build();

        List<ReservedSeat> reservedSeats = seats.stream()
                .map(seat -> ReservedSeat.builder().reservation(reservation).seat(seat).build())
                .toList();

        reservation.setReservedSeats(reservedSeats);

        reservationRepository.save(reservation);

        return ReservationCreateResponse.from(reservation);

    }

    @Transactional
    public ReservationCreateResponse createReservationWithOptimistic(String walletAddress, ReservationRequest request) {
        Member member = memberRepository.findByWalletAddress(walletAddress)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        List<Seat> seats = seatService.findAndLockSeatsByIdsWithOptimistic(request.getSeatIds());
        checkSeatsAvailability(seats);

        seatService.changeSeatsState(seats, LOCKED);

        int totalPrice = seats.stream().mapToInt(Seat::getPrice).sum();

        String reservationCode = member.makeReservationCode();

        Reservation reservation = Reservation.builder()
                .totalPrice(totalPrice)
                .member(member)
                .reservationCode(reservationCode)
                .reservationStatus(PENDING_PAYMENT)
                .expiredTime(LocalDateTime.now().plusMinutes(7L))
                .build();

        List<ReservedSeat> reservedSeats = seats.stream()
                .map(seat -> ReservedSeat.builder().reservation(reservation).seat(seat).build())
                .toList();

        reservation.setReservedSeats(reservedSeats);

        reservationRepository.save(reservation);

        return ReservationCreateResponse.from(reservation);

    }


    /**
     *
//     * @param reservationId
     * @return
     * @throws IamportResponseException
     * @throws IOException
     *
     *    1. reservationRepository.findById(reservationId): 예약 정보 조회
     *    2. reservation.getReservedSeats() -> map(ReservedSeat::getSeat): 예약된 좌석 정보 조회 (N+1 문제 발생 가능)
     *    3. reservationRepository.findByPerformance(reservationId): 공연 정보 조회
     *    4. reservationRepository.findByWalletAddressByOrganizer(reservationId): 주최사 지갑 주소 조회
     *    해당 부분에서 여러번의 쿼리로, 성능 저하가 될 수 있음, 해서 한 번의 쿼리로 모든 정보를 가져오도록 변경
     *
     */
/*    @Transactional
    public ReservationSuccessResponse confirmReservation(Long reservationId) throws IamportResponseException, IOException {


        Reservation reservation = reservationRepository.findByIdWithDetails(reservationId).
                orElseThrow(() -> new EntityNotFoundException("예약 정보를 확인 할 수 없습니다."));


        if(!reservation.getReservationStatus().equals(PENDING_PAYMENT)) {
            throw new EntityNotFoundException("결제 정보를 확인할 수 없거나 , 결제가 완료된 티켓입니다."); // custom Exception
        }

        List<Seat> seats = reservation.getReservedSeats().stream()
                .map(ReservedSeat::getSeat)// 2
                .toList();

        Performance performance = reservation.getReservedSeats().
                getFirst().getSeat().getPerformanceTime().getPerformance();

        reservation.changeReservationStatus(SUCCESS);

        seatService.changeSeatsState(seats, LOCKED);

        String byWalletAddressByOrganizer =
                reservation.getReservedSeats().getFirst().getSeat().getPerformanceTime().
                getPerformance().getOrganizer().getMember().getWalletAddress();

        return ReservationSuccessResponse.from(reservation, performance, byWalletAddressByOrganizer);
    }*/

    public void checkSeatsAvailability(List<Seat> seats) {

        boolean isReserved = seats.stream()
                .anyMatch(seat -> seat.getSeatStatus().equals(RESERVED) ||
                        seat.getSeatStatus().equals(UNAVAILABLE) ||
                        seat.getSeatStatus().equals(LOCKED));


        if (isReserved) throw new EntityExistsException("이미 예약 완료된 좌석입니다."); // customException

    }


}

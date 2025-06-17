package org.example.ticket.reservation.service;


import com.siot.IamportRestClient.exception.IamportResponseException;
import com.siot.IamportRestClient.response.IamportResponse;
import com.siot.IamportRestClient.response.Payment;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.payment.model.Settlement;
import org.example.ticket.payment.request.VerifyPaymentRequest;
import org.example.ticket.payment.service.PaymentService;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.reservation.response.ReservationCreateResponse;
import org.example.ticket.reservation.model.Reservation;
import org.example.ticket.reservation.model.ReservedSeat;
import org.example.ticket.reservation.model.Seat;
import org.example.ticket.reservation.request.ReservationRequest;
import org.example.ticket.reservation.repository.ReservationRepository;
import org.example.ticket.reservation.response.ReservationSuccessResponse;
import org.example.ticket.util.constant.ReservationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final MemberRepository memberRepository;
    private final PaymentService paymentService;
    private final SeatService seatService;

    @Transactional
    public ReservationCreateResponse createReservation(String walletAddress, ReservationRequest request) {
        Member member = memberRepository.findByWalletAddress(walletAddress)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        List<Seat> seats = seatService.findAndLockSeatsByIds(request.getSeatIds());
        checkSeatsAvailability(seats);


        int totalPrice = seats.stream().mapToInt(Seat::getPrice).sum();

        String reservationCode = member.makeReservationCode();

        Reservation reservation = Reservation.builder()
                .totalPrice(totalPrice)
                .member(member)
                .reservationCode(reservationCode)
                .reservationStatus(ReservationStatus.PENDING_PAYMENT)
                .build();

        List<ReservedSeat> reservedSeats = seats.stream()
                .map(seat -> ReservedSeat.builder().reservation(reservation).seat(seat).build())
                .toList();

        reservation.setReservedSeats(reservedSeats);

        reservationRepository.save(reservation);

        return ReservationCreateResponse.from(reservation);
    }


    @Transactional
    public ReservationSuccessResponse confirmReservation(Long reservationId, VerifyPaymentRequest request) throws IamportResponseException, IOException {

        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new EntityNotFoundException("예약 정보를 확인할 수 없습니다."));


        if(!reservation.getReservationStatus().equals(ReservationStatus.PENDING_PAYMENT)) {
            throw new EntityNotFoundException("결제 정보를 확인할 수 없거나 , 결제가 완료된 티켓입니다."); // custom Exception
        }

        List<Seat> seats = reservation.getReservedSeats().stream()
                .map(ReservedSeat::getSeat)
                .toList();

        Performance performance = reservationRepository.findByPerformance(reservationId);
        IamportResponse<Payment> paymentIamportResponse = paymentService.verifyPayment(request, reservation.getTotalPrice());

        if(paymentIamportResponse == null) {
            throw new RuntimeException("결제에 실패하였습니다.");
        }

        reservation.changeReservationStatus(ReservationStatus.SUCCESS);
        Settlement payment = paymentService.initialPayment(request, reservation.getTotalPrice());

        reservation.completeSuccessPayment(payment);
        seatService.changeSeatsState(seats);
        String byWalletAddressByOrganizer = reservationRepository.findByWalletAddressByOrganizer(reservationId);

        return ReservationSuccessResponse.from(reservation, performance, byWalletAddressByOrganizer);
    }

    public void checkSeatsAvailability(List<Seat> seats) {

        boolean isReserved = seats.stream()
                .anyMatch(seat -> Boolean.TRUE.equals(seat.getIsReservation()));


        if (isReserved) throw new EntityExistsException("이미 예약 완료된 좌석입니다."); // customException

    }

}

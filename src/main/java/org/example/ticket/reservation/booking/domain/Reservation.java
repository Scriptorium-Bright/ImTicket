package org.example.ticket.reservation.booking.domain;

import jakarta.persistence.*;
import lombok.*;
import org.example.ticket.member.model.Member;
//import org.example.ticket.payment.model.Settlement;
import org.example.ticket.util.constant.ReservationStatus;
import org.hibernate.annotations.CurrentTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(indexes = {
        @Index(name = "idx_reservation_status_expired_time", columnList = "reservation_status, reservation_expired_time")
})
@Builder
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_code", nullable = false, unique = true)
    private String reservationCode;

    @Column(name = "total_price", nullable = false)
    private Integer totalPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_status", nullable = false)
    private ReservationStatus reservationStatus;

    @CurrentTimestamp
    @Column(name = "reservation_date", updatable = false)
    private LocalDateTime reservationDateTime;

    @Column(name = "reservation_expired_time")
    private LocalDateTime expiredTime;

    @Builder.Default
    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL)
    private List<ReservedSeat> reservedSeats = new ArrayList<>();

    /**
     * 예약의 현재 상태를 지정한 값으로 변경한다.
     * 결제 대기 만료 시각은 기존 값을 유지한다.
     */
    public void changeReservationStatus(ReservationStatus reservationStatus) {
        this.reservationStatus = reservationStatus;
    }

    /**
     * 예약에 연결된 예약 좌석 목록을 교체한다.
     * 예약 생성 시 구성한 양방향 관계를 aggregate에 반영한다.
     */
    public void setReservedSeats(List<ReservedSeat> reservedSeats) {
        this.reservedSeats = reservedSeats;
    }

    /**
     * 예약 상태와 결제 대기 만료 시각을 함께 변경한다.
     * 결제 완료 시에는 만료 시각을 비워 종결 상태를 표현한다.
     */
    public void manageReservationStatus(ReservationStatus reservationStatus, LocalDateTime expiredTime) {
        this.reservationStatus = reservationStatus;
        this.expiredTime = expiredTime;
    }

    /**
     * 결제 대기 중인 예약을 만료 상태로 전환한다.
     * 다른 상태에서 호출되면 도메인 상태 위반으로 거절한다.
     */
    public void expire() {
        if (reservationStatus != ReservationStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("결제 대기 예약만 만료할 수 있습니다.");
        }
        this.reservationStatus = ReservationStatus.EXPIRED;
    }

/*

    @OneToOne
    @JoinColumn(name = "settlement_id")
    private Settlement payment;

    public void completeSuccessPayment(Settlement payment) {
        this.payment = payment;
    }
*/

}

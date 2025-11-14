package org.example.ticket.reservation.model;

import jakarta.persistence.*;
import lombok.*;
import org.example.ticket.member.model.Member;
import org.example.ticket.util.constant.ReservationStatus;
import org.hibernate.annotations.CurrentTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Builder
@Getter
@Setter
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

    public void changeReservationStatus(ReservationStatus reservationStatus) {
        this.reservationStatus = reservationStatus;
    }

//    public void completeSuccessPayment(Settlement payment) {
//        this.payment = payment;
//    }

}

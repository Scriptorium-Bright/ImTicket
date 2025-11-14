package org.example.ticket.reservation.model;

import jakarta.persistence.*;
import lombok.*;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.reservation.request.SeatRequest;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;

import static org.example.ticket.util.constant.SeatStatus.LOCKED;

@Entity
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(name = "seat_floor", nullable = false)
    private Integer seatFloor;

    @Column(name = "seat_section", nullable = false)
    private String seatSection;

    @Column(name = "seat_row", nullable = false)
    private Integer seatRow;

    @Column(name = "seat_number", nullable = false)
    private Integer seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", nullable = false)
    private SeatInfo seatType;

    @Column(name = "seat_price", nullable = false)
    private Integer price;

    @Column(name = "is_reservation")
    private Boolean isReservation;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_status")
    private SeatStatus seatStatus;

/*    @Version
    private Long version;*/

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performance_time_id")
    private PerformanceTime performanceTime; // 이 좌석이 속한 특정 공연 회차

    public static Seat from(SeatRequest seat, Boolean isReservation) {
        return Seat.builder()
                .seatFloor(seat.getSeatFloor())
                .seatSection(seat.getSeatSection())
                .seatRow(seat.getSeatRow())
                .seatNumber(seat.getSeatNumber())
                .seatType(seat.getSeatType())
                .price(seat.getPrice())
                .isReservation(isReservation)
                .build();
    }

    public void markAsReserved(SeatStatus seatStatus) {
        this.seatStatus = seatStatus;
    }

    public void forIncreaseSeatNumber(Integer seatNumber) {
        this.seatNumber = seatNumber;
    }

}

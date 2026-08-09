package org.example.ticket.reservation.booking.domain;

import jakarta.persistence.*;
import lombok.*;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.reservation.booking.dto.SeatCreationData;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;

@Entity
@Table(indexes = {
        @Index(name = "idx_seat_perf_status", columnList = "performance_time_id, seat_status")
}, uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_seat_performance_position",
                columnNames = {"performance_time_id", "seat_floor", "seat_section", "seat_row", "seat_number"}
        )
})
@Builder
@Getter
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

    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performance_time_id", nullable = false)
    private PerformanceTime performanceTime; // 이 좌석이 속한 특정 공연 회차

    /**
     * 내부 좌석 생성 데이터를 JPA Seat entity로 변환한다.
     * 좌석 배치와 가격을 복사하고 초기 예약 여부를 적용한다.
     */
    public static Seat from(SeatCreationData seat, Boolean isReservation) {
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

    /**
     * 좌석의 현재 예약 가능 상태를 지정한 값으로 변경한다.
     * 예약 생성, 결제 완료와 만료 정리가 같은 도메인 변경 메서드를 사용한다.
     */
    public void markAsReserved(SeatStatus seatStatus) {
        this.seatStatus = seatStatus;
    }

}

package org.example.ticket.reservation.booking.domain;


import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_reserved_seat_reservation_seat",
                columnNames = {"reservation_id", "seat_id"}
        )
})
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ReservedSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

}

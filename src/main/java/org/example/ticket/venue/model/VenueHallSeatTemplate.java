package org.example.ticket.venue.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.util.constant.SeatInfo;

@Entity
@Table(
        name = "venue_hall_seat_template",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_hall_seat_template_position",
                        columnNames = {"venue_hall_id", "seat_floor", "seat_section", "seat_row", "seat_number"}
                )
        }
)
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class VenueHallSeatTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_hall_id", nullable = false)
    private VenueHall venueHall;

    @Column(name = "seat_floor", nullable = false)
    private Integer floor;

    @Column(name = "seat_section", nullable = false)
    private String section;

    @Column(name = "seat_row", nullable = false)
    private Integer row;

    @Column(name = "seat_number", nullable = false)
    private Integer seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", nullable = false)
    private SeatInfo seatInfo;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "seat_attributes")
    private String attributes;
}

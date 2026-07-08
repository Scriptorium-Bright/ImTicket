package org.example.ticket.performance.model;

import jakarta.persistence.*;
import lombok.*;
import org.example.ticket.util.constant.SeatInfo;

@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_seat_price_performance_type",
                columnNames = {"performance_id", "seat_type"}
        )
})
@Builder
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SeatPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", nullable = false)
    private SeatInfo seatInfo;

    @Column(name = "seat_price", nullable = false)
    private Integer price;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performance_id", nullable = false)
    private Performance performance;

}

package org.example.ticket.performance.model;

import jakarta.persistence.*;
import lombok.*;
import org.example.ticket.util.constant.SeatInfo;

@Entity
@Builder
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SeatPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seat_type", nullable = false)
    private SeatInfo seatInfo;

    @Column(name = "seat_price", nullable = false)
    private Integer price;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performance_id")
    private Performance performance;

}

package org.example.ticket.venue.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VenueHall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "venuehall_name", nullable = false)
    private String name;

    @Column(name = "venuehall_total_seats")
    private Integer totalSeats;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id")
    private Venue venue;

    @Builder.Default
    @OneToMany(mappedBy = "venueHall", cascade = CascadeType.ALL)
    private List<VenueHallFloor> floorList = new ArrayList<>();
}

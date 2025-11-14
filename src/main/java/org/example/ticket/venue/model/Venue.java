package org.example.ticket.venue.model;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Entity
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "performance_venue_name", nullable = false, unique = true)
    private String name;

    @Column(name = "performance_place_address", nullable = false)
    private String address;

    private String phoneNumber;

    @Builder.Default
    @OneToMany(mappedBy = "venue", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VenueHall> venueHalls = new ArrayList<>();

    public void addHall(VenueHall hall) {
        this.venueHalls.add(hall);
        hall.setVenue(this); // 자식에게도 부모(나 자신)를 설정하여 양쪽 관계를 모두 설정
    }

}

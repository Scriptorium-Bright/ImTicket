package org.example.ticket.performance.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.member.model.Organizer;
import org.example.ticket.util.constant.VenueType;
import org.example.ticket.performance.dto.request.PerformanceDetailRequest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Entity
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Performance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "visible_age")
    private Integer ageLimit;

    @Lob
    private String description;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "performance_title", nullable = false)
    private String title;

    @Column(name = "venue_type")
    private VenueType venueType;

    @Column(name = "performance_start_date")
    private LocalDate startDate;

    @Column(name = "performance_end_date")
    private LocalDate endDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organizer_id")
    public Organizer organizer;

    @OneToMany(mappedBy = "performance",  cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PerformanceTime> performanceTimes = new ArrayList<>();

    @OneToMany(mappedBy = "performance" , cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SeatPrice> seatPrices = new ArrayList<>();

    public static Performance from(PerformanceDetailRequest request) {
        return Performance.builder()
                .ageLimit(request.getAge())
                .description(request.getDescription())
                .imageUrl(request.getImageUrl())
                .title(request.getTitle())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .venueType(request.getVenueType())
                .build();
    }

}


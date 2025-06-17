package org.example.ticket.member.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.util.constant.OrganizerType;

import java.util.ArrayList;
import java.util.List;

@Entity
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Organizer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "organizer_type")
    private OrganizerType organizerType;

    @Column(name = "organizer_name", unique = true)
    private String organizerName;

    @Column(name = "company_address")
    private String address;

    @Column(name = "organizer_email_address")
    private String contactEmail;

    @Column(name = "company_business_number", nullable = false)
    private String businessNumber;


    @OneToOne(fetch = FetchType.LAZY, orphanRemoval = true, cascade = CascadeType.ALL)
    @JoinColumn(name = "member_id")
    private Member member;

    @OneToMany(mappedBy = "organizer")
    private List<Performance> performances = new ArrayList<>();

}

package org.example.ticket.member.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.util.constant.organizerType;

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
    private organizerType organizerType;

    @Column(name = "organizer_name", unique = true)
    private String organizerName;

    @Column(name = "organizer_wallet_address", unique = true)
    private String walletAddress;

    @Column(name = "company_address")
    private String address;

    @Column(name = "organizer_email_address")
    private String contactEmail;

    @Column(name = "company_business_number", nullable = false)
    private String businessNumber;

    @Column(name = "organizer_phone_number")
    private String contactPhone;

    @OneToMany(mappedBy = "organizer")
    private List<Performance> performances = new ArrayList<>();

}

package org.example.ticket.member.model;

import jakarta.persistence.*;
import lombok.*;
import org.web3j.abi.datatypes.Int;

import java.util.UUID;

@Getter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, name = "wallet_address", unique = true)
    private String walletAddress;
    @Column(nullable = false, name = "phone_number")
    private String phoneNumber;
    @Column(nullable = false, name = "user_role")
    private String role;
    @Column(name = "sms_verified")
    private Boolean smsVerified;
    @Column(name = "wallet_verified")
    private Boolean walletVerified;
    @Column(name = "nonce")
    private Integer nonce;
    @Column(name = "identify_name", unique = true)
    private String nickname;

    @OneToOne(mappedBy = "member")
    private Organizer organizer;

    public String makeReservationCode() {
        return UUID.randomUUID().toString();
    }

    public void updateNonce(Integer newNonce) { this.nonce = newNonce; }

    public void changeMembersRole(String role) {
        this.role = role;
    }

}

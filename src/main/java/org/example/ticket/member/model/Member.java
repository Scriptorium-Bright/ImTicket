package org.example.ticket.member.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
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
    @Column(nullable = true, name = "phone_number")
    private String phoneNumber;
    @Column(nullable = false, name = "user_role")
    private String role;
    @Column(name = "sms_verified")
    private Boolean smsVerified;
    @Column(name = "wallet_verified")
    private Boolean walletVerified;
    @Column(name = "nonce", length = 64)
    private String nonce;
    @Column(name = "nonce_issued_at")
    private Instant nonceIssuedAt;
    @Column(name = "nonce_expires_at")
    private Instant nonceExpiresAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "nonce_purpose", length = 20)
    private NoncePurpose noncePurpose;
    @Column(name = "identify_name", unique = true)
    private String nickname;

    @OneToOne(mappedBy = "member")
    private Organizer organizer;



    public void updateNonce(String newNonce, NoncePurpose purpose, Instant issuedAt, Instant expiresAt) {
        this.nonce = newNonce;
        this.noncePurpose = purpose;
        this.nonceIssuedAt = issuedAt;
        this.nonceExpiresAt = expiresAt;
    }

    public void clearNonce() {
        this.nonce = null;
        this.noncePurpose = null;
        this.nonceIssuedAt = null;
        this.nonceExpiresAt = null;
    }

    public boolean isRegistered() {
        return Boolean.TRUE.equals(smsVerified)
                && Boolean.TRUE.equals(walletVerified)
                && phoneNumber != null
                && nickname != null;
    }

    public void completeRegistration(String phoneNumber, String nickname, String role) {
        this.phoneNumber = phoneNumber;
        this.nickname = nickname;
        this.smsVerified = true;
        this.walletVerified = true;
        this.role = role;
        clearNonce();
    }

    public void changeMembersRole(String role) {
        this.role = role;
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

}

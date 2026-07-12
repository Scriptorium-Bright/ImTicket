package org.example.ticket.member.repository;

import org.example.ticket.member.model.Member;
import org.example.ticket.member.model.NoncePurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByWalletAddressOrPhoneNumber(String walletAddress, String phoneNumber);

    Boolean existsMemberByWalletAddress(String walletAddress);
    Optional<Member> findByWalletAddress(String walletAddress);
    Optional<Member> findByWalletAddressIgnoreCase(String walletAddress);
    boolean existsMemberByPhoneNumber(String phoneNumber);
    Member findByNickname(String nickname);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Member m
            set m.nonce = null,
                m.nonceIssuedAt = null,
                m.nonceExpiresAt = null,
                m.noncePurpose = null
            where lower(m.walletAddress) = lower(:walletAddress)
              and m.nonce = :nonce
              and m.noncePurpose = :purpose
              and m.nonceExpiresAt > :now
            """)
    int consumeNonce(@Param("walletAddress") String walletAddress,
                     @Param("nonce") String nonce,
                     @Param("purpose") NoncePurpose purpose,
                     @Param("now") Instant now);

    @Query("SELECT m.nickname FROM Member m WHERE m.walletAddress = :walletAddress")
    String findNicknameByWalletAddress(String walletAddress);

    @Query("SELECT m.walletAddress FROM Member m WHERE m.nickname = :nickname")
    String findWalletAddressByNickname(String nickname);
}

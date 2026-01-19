package org.example.ticket.member.repository;

import org.example.ticket.member.model.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByWalletAddressOrPhoneNumber(String walletAddress, String phoneNumber);

    Boolean existsMemberByWalletAddress(String walletAddress);
    Optional<Member> findByWalletAddress(String walletAddress);
    boolean existsMemberByPhoneNumber(String phoneNumber);
    Member findByNickname(String nickname);

    @Query("SELECT m.nickname FROM Member m WHERE m.walletAddress = :walletAddress")
    String findNicknameByWalletAddress(String walletAddress);

    @Query("SELECT m.walletAddress FROM Member m WHERE m.nickname = :nickname")
    String findWalletAddressByNickname(String nickname);
}
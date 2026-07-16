package org.example.ticket.payment.repository;

import jakarta.persistence.LockModeType;
import org.example.ticket.payment.model.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    @Query("""
            select po
            from PaymentOrder po
            join fetch po.reservation r
            join fetch po.member m
            where po.id = :id
            """)
    Optional<PaymentOrder> findByIdWithOwner(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select po from PaymentOrder po where po.id = :id")
    Optional<PaymentOrder> findByIdForUpdate(@Param("id") Long id);

    Optional<PaymentOrder> findByMemberIdAndIdempotencyKey(Long memberId, String idempotencyKey);
}

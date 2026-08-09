package org.example.ticket.payment.model;

import jakarta.persistence.*;
import lombok.*;
import org.example.ticket.member.model.Member;
import org.example.ticket.payment.constant.PaymentOrderStatus;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment_order", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_order_reservation", columnNames = "reservation_id"),
        @UniqueConstraint(name = "uk_payment_order_merchant_order", columnNames = "merchant_order_id"),
        @UniqueConstraint(name = "uk_payment_order_member_idempotency", columnNames = {"member_id", "idempotency_key"})
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "merchant_order_id", nullable = false, unique = true, length = 100)
    private String merchantOrderId;

    @Column(name = "amount", nullable = false)
    private Integer amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentOrderStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void markPaidUnapplied() {
        this.status = PaymentOrderStatus.PAID_UNAPPLIED;
    }

    public void markApplied() {
        this.status = PaymentOrderStatus.APPLIED;
    }

    public void markRefundPending() {
        this.status = PaymentOrderStatus.REFUND_PENDING;
    }
}

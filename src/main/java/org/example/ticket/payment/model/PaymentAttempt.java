package org.example.ticket.payment.model;

import jakarta.persistence.*;
import lombok.*;
import org.example.ticket.payment.constant.PaymentAttemptStatus;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment_attempt", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_attempt_id", columnNames = "attempt_id"),
        @UniqueConstraint(name = "uk_payment_attempt_provider_transaction", columnNames = "provider_transaction_id")
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_order_id", nullable = false)
    private PaymentOrder paymentOrder;

    @Column(name = "attempt_id", nullable = false, unique = true, length = 100)
    private String attemptId;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_transaction_id", unique = true, length = 150)
    private String providerTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentAttemptStatus status;

    @Column(name = "approved_amount")
    private Integer approvedAmount;

    @Column(name = "approved_currency", length = 3)
    private String approvedCurrency;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void markPaid(String providerTransactionId, int approvedAmount,
                         String approvedCurrency, LocalDateTime approvedAt) {
        this.providerTransactionId = providerTransactionId;
        this.approvedAmount = approvedAmount;
        this.approvedCurrency = approvedCurrency;
        this.approvedAt = approvedAt;
        this.status = PaymentAttemptStatus.PAID;
    }
}

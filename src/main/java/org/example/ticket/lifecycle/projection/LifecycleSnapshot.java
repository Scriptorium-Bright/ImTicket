package org.example.ticket.lifecycle.projection;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** 한 예약의 현재 예약·결제 상태와 경로를 저장하는 조회 모델 요약이다. */
@Entity
@Table(name = "lifecycle_snapshot", indexes = {
        @Index(name = "idx_lifecycle_snapshot_payment_order", columnList = "projection_version, payment_order_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LifecycleSnapshot {

    @EmbeddedId
    private LifecycleProjectionKey id;

    @Column(name = "payment_order_id")
    private Long paymentOrderId;

    @Column(name = "reservation_status", length = 30)
    private String reservationStatus;

    @Column(name = "payment_order_status", length = 30)
    private String paymentOrderStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "path_classification", nullable = false, length = 40)
    private LifecyclePathClassification pathClassification;

    @Enumerated(EnumType.STRING)
    @Column(name = "trust_status", nullable = false, length = 20)
    private LifecycleTrustStatus trustStatus;

    @Column(name = "last_applied_version", nullable = false)
    private long lastAppliedVersion;

    @Column(name = "reconciled_source_version")
    private Long reconciledSourceVersion;

    @Column(name = "reconciled_event_version")
    private Long reconciledEventVersion;

    @Column(name = "reconciliation_diff", columnDefinition = "json")
    private String reconciliationDiff;

    @Column(name = "last_reconciled_at")
    private LocalDateTime lastReconciledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private LifecycleSnapshot(int projectionVersion, Long lifecycleId) {
        this.id = new LifecycleProjectionKey(projectionVersion, lifecycleId);
        this.pathClassification = LifecyclePathClassification.IN_PROGRESS;
        this.trustStatus = LifecycleTrustStatus.PROCESSING;
        this.lastAppliedVersion = 0L;
    }

    public static LifecycleSnapshot empty(int projectionVersion, Long lifecycleId) {
        return new LifecycleSnapshot(projectionVersion, lifecycleId);
    }

    public void setPaymentOrderId(Long paymentOrderId) {
        if (paymentOrderId != null) {
            this.paymentOrderId = paymentOrderId;
        }
    }

    public void setReservationStatus(String reservationStatus) {
        this.reservationStatus = reservationStatus;
    }

    public void setPaymentOrderStatus(String paymentOrderStatus) {
        this.paymentOrderStatus = paymentOrderStatus;
    }

    public void setPathClassification(LifecyclePathClassification pathClassification) {
        this.pathClassification = pathClassification;
    }

    public void setTrustStatus(LifecycleTrustStatus trustStatus) {
        this.trustStatus = trustStatus;
    }

    public void setLastAppliedVersion(long lastAppliedVersion) {
        this.lastAppliedVersion = lastAppliedVersion;
    }

    public void markReconciled(
            long sourceVersion,
            long eventVersion,
            String reconciliationDiff,
            LifecycleTrustStatus trustStatus
    ) {
        this.reconciledSourceVersion = sourceVersion;
        this.reconciledEventVersion = eventVersion;
        this.reconciliationDiff = reconciliationDiff;
        this.lastReconciledAt = LocalDateTime.now();
        this.trustStatus = trustStatus;
    }
}

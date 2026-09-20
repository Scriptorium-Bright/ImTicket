package org.example.ticket.lifecycle.projection;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 조회 모델에서 결제 시도 한 건의 현재 상태와 승인 부가 정보다. */
@Entity
@Table(name = "lifecycle_payment_attempt_snapshot", indexes = {
        @Index(name = "idx_lifecycle_attempt_snapshot_lifecycle", columnList = "projection_version, lifecycle_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LifecyclePaymentAttemptSnapshot {

    @EmbeddedId
    private LifecyclePaymentAttemptSnapshotId id;

    @Column(name = "payment_attempt_status", nullable = false, length = 30)
    private String paymentAttemptStatus;

    @Column(name = "provider_transaction_id", length = 150)
    private String providerTransactionId;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    private LifecyclePaymentAttemptSnapshot(
            LifecyclePaymentAttemptSnapshotId id,
            String paymentAttemptStatus
    ) {
        this.id = id;
        this.paymentAttemptStatus = paymentAttemptStatus;
    }

    public static LifecyclePaymentAttemptSnapshot create(
            int projectionVersion,
            Long lifecycleId,
            Long paymentAttemptId,
            String paymentAttemptStatus
    ) {
        return new LifecyclePaymentAttemptSnapshot(
                new LifecyclePaymentAttemptSnapshotId(projectionVersion, lifecycleId, paymentAttemptId),
                paymentAttemptStatus
        );
    }

    public void setPaymentAttemptStatus(String paymentAttemptStatus) {
        this.paymentAttemptStatus = paymentAttemptStatus;
    }
}

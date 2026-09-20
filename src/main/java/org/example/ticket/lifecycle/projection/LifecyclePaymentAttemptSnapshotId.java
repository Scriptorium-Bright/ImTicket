package org.example.ticket.lifecycle.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 조회 모델의 예약·결제 시도 복합 키다. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LifecyclePaymentAttemptSnapshotId {

    @Column(name = "projection_version")
    private int projectionVersion;

    @Column(name = "lifecycle_id")
    private Long lifecycleId;

    @Column(name = "payment_attempt_id")
    private Long paymentAttemptId;
}

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

import java.time.LocalDateTime;

/** 조회 모델 버전별 사건 적용 결과와 실패 근거다. */
@Entity
@Table(name = "lifecycle_event_application", indexes = {
        @Index(name = "idx_lifecycle_event_application_pending", columnList = "projection_version, status, lifecycle_id, decision_version")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LifecycleEventApplication {

    @EmbeddedId
    private LifecycleEventApplicationId id;

    @Column(name = "lifecycle_id", nullable = false)
    private Long lifecycleId;

    @Column(name = "decision_version", nullable = false)
    private long decisionVersion;

    @Column(name = "event_ordinal", nullable = false)
    private int eventOrdinal;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LifecycleApplicationStatus status;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", nullable = false, length = 20)
    private LifecycleApplicationErrorType errorType;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempted_at")
    private LocalDateTime lastAttemptedAt;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LifecycleEventApplication(
            LifecycleEventApplicationId id,
            Long lifecycleId,
            long decisionVersion,
            int eventOrdinal,
            LifecycleApplicationStatus status
    ) {
        this.id = id;
        this.lifecycleId = lifecycleId;
        this.decisionVersion = decisionVersion;
        this.eventOrdinal = eventOrdinal;
        this.status = status;
        this.errorType = LifecycleApplicationErrorType.NONE;
        this.attemptCount = 0;
    }

    public static LifecycleEventApplication pending(
            int projectionVersion,
            String eventId,
            Long lifecycleId,
            long decisionVersion,
            int eventOrdinal
    ) {
        return new LifecycleEventApplication(
                new LifecycleEventApplicationId(projectionVersion, eventId),
                lifecycleId,
                decisionVersion,
                eventOrdinal,
                LifecycleApplicationStatus.PENDING
        );
    }

    public void markApplied() {
        this.status = LifecycleApplicationStatus.APPLIED;
        this.errorCode = null;
        this.errorType = LifecycleApplicationErrorType.NONE;
        this.attemptCount++;
        this.lastAttemptedAt = LocalDateTime.now();
        this.nextAttemptAt = null;
        this.appliedAt = LocalDateTime.now();
    }

    public void markDuplicate() {
        this.status = LifecycleApplicationStatus.DUPLICATE;
        this.errorCode = null;
        this.errorType = LifecycleApplicationErrorType.NONE;
        this.lastAttemptedAt = LocalDateTime.now();
        this.nextAttemptAt = null;
        this.appliedAt = LocalDateTime.now();
    }

    public void markFailed(String errorCode) {
        markFailed(errorCode, LifecycleApplicationErrorType.CONTRACT, null);
    }

    public void markFailed(
            String errorCode,
            LifecycleApplicationErrorType errorType,
            LocalDateTime nextAttemptAt
    ) {
        this.status = LifecycleApplicationStatus.FAILED;
        this.errorCode = errorCode;
        this.errorType = errorType;
        this.attemptCount++;
        this.lastAttemptedAt = LocalDateTime.now();
        this.nextAttemptAt = nextAttemptAt;
        this.appliedAt = null;
    }
}

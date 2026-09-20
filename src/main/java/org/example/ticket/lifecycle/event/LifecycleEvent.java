package org.example.ticket.lifecycle.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** 업무 상태와 같은 트랜잭션에서 기록하는 불변 Lifecycle 사건 원본이다. */
@Entity
@Table(name = "lifecycle_event",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_lifecycle_event_event_id", columnNames = "event_id"),
                @UniqueConstraint(
                        name = "uk_lifecycle_event_decision_ordinal",
                        columnNames = {"lifecycle_id", "decision_version", "event_ordinal"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_lifecycle_event_lifecycle_decision",
                        columnList = "lifecycle_id, decision_version, event_ordinal"
                ),
                @Index(name = "idx_lifecycle_event_payment_order", columnList = "payment_order_id, decision_version"),
                @Index(name = "idx_lifecycle_event_commit_group", columnList = "commit_group_id, lifecycle_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LifecycleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "lifecycle_id", nullable = false)
    private Long lifecycleId;

    @Column(name = "payment_order_id")
    private Long paymentOrderId;

    @Column(name = "payment_attempt_id")
    private Long paymentAttemptId;

    @Column(name = "decision_version", nullable = false)
    private Long decisionVersion;

    @Column(name = "event_ordinal", nullable = false)
    private int eventOrdinal;

    @Column(name = "commit_group_id", nullable = false, length = 36)
    private String commitGroupId;

    @Column(name = "actor_type", nullable = false, length = 40)
    private String actorType;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    @Column(name = "payload", nullable = false, columnDefinition = "json")
    private String payload;

    @Builder
    private LifecycleEvent(
            String eventId,
            String eventType,
            int schemaVersion,
            Long lifecycleId,
            Long paymentOrderId,
            Long paymentAttemptId,
            Long decisionVersion,
            int eventOrdinal,
            String commitGroupId,
            String actorType,
            LocalDateTime occurredAt,
            String payload
    ) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.lifecycleId = lifecycleId;
        this.paymentOrderId = paymentOrderId;
        this.paymentAttemptId = paymentAttemptId;
        this.decisionVersion = decisionVersion;
        this.eventOrdinal = eventOrdinal;
        this.commitGroupId = commitGroupId;
        this.actorType = actorType;
        this.occurredAt = occurredAt;
        this.payload = payload;
    }
}

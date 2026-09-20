package org.example.ticket.lifecycle.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** 별도 조회 모델 버전을 다시 만드는 Replay 실행 기록이다. */
@Entity
@Table(name = "lifecycle_replay_run", indexes = {
        @Index(name = "idx_lifecycle_replay_run_target", columnList = "projection_version, lifecycle_id, status, requested_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LifecycleReplayRun {

    @Id
    @Column(name = "run_id", length = 36)
    private String runId;

    @Column(name = "projection_version", nullable = false)
    private int projectionVersion;

    @Column(name = "lifecycle_id", nullable = false)
    private Long lifecycleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LifecycleReplayRunStatus status;

    @CreationTimestamp
    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "processed_events", nullable = false)
    private int processedEvents;

    @Column(name = "failed_events", nullable = false)
    private int failedEvents;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    private LifecycleReplayRun(String runId, int projectionVersion, Long lifecycleId) {
        this.runId = runId;
        this.projectionVersion = projectionVersion;
        this.lifecycleId = lifecycleId;
        this.status = LifecycleReplayRunStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
    }

    public static LifecycleReplayRun start(String runId, int projectionVersion, Long lifecycleId) {
        return new LifecycleReplayRun(runId, projectionVersion, lifecycleId);
    }

    public void recordProcessedEvent(int count) {
        this.processedEvents += count;
    }

    public void recordFailure() {
        this.failedEvents++;
    }

    public void complete() {
        this.status = LifecycleReplayRunStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String errorMessage) {
        this.status = LifecycleReplayRunStatus.FAILED;
        this.errorMessage = errorMessage == null ? "알 수 없는 Replay 오류" : errorMessage.substring(0, Math.min(500, errorMessage.length()));
        this.completedAt = LocalDateTime.now();
    }
}

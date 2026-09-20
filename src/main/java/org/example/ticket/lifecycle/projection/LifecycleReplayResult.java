package org.example.ticket.lifecycle.projection;

/** 한 Lifecycle을 별도 조회 모델 버전으로 Replay한 결과다. */
public record LifecycleReplayResult(
        String runId,
        Long lifecycleId,
        int projectionVersion,
        LifecycleReplayRunStatus status,
        int processedEvents,
        int failedEvents
) {
}

package org.example.ticket.lifecycle.projection;

/** 한 결정 단위를 재구성한 결과 요약이다. */
public record LifecycleReconstructionResult(
        Long lifecycleId,
        long decisionVersion,
        int eventCount,
        LifecycleApplicationStatus applicationStatus,
        LifecyclePathClassification pathClassification,
        LifecycleTrustStatus trustStatus
) {
}

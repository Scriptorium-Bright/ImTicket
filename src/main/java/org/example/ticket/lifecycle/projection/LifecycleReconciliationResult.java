package org.example.ticket.lifecycle.projection;

import java.util.Map;

/** 원천 업무 상태와 Lifecycle 조회 모델을 대조한 결과다. */
public record LifecycleReconciliationResult(
        Long lifecycleId,
        int projectionVersion,
        long sourceVersion,
        long eventVersion,
        LifecycleTrustStatus trustStatus,
        Map<String, Object> differences
) {
}

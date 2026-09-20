package org.example.ticket.lifecycle.event;

/** Poller와 Consumer가 분리하지 않고 읽어야 하는 Lifecycle 업무 결정의 키다. */
public record LifecycleEventDecisionKey(Long lifecycleId, Long decisionVersion) {
    public LifecycleEventDecisionKey {
        if (lifecycleId == null || lifecycleId <= 0) {
            throw new IllegalArgumentException("lifecycleId는 양수여야 합니다.");
        }
        if (decisionVersion == null || decisionVersion <= 0) {
            throw new IllegalArgumentException("decisionVersion은 양수여야 합니다.");
        }
    }
}

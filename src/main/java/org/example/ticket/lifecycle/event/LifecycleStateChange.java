package org.example.ticket.lifecycle.event;

import java.util.Objects;

/** 한 업무 사건에 포함되는 Entity 상태 전이다. */
public record LifecycleStateChange(
        LifecycleEntityType entityType,
        String entityId,
        String fromState,
        String toState
) {
    public LifecycleStateChange {
        Objects.requireNonNull(entityType, "entityType은 필수입니다.");
        requireText(entityId, "entityId");
        requireText(toState, "toState");
    }

    public static LifecycleStateChange created(
            LifecycleEntityType entityType,
            Long entityId,
            String toState
    ) {
        return new LifecycleStateChange(entityType, String.valueOf(entityId), null, toState);
    }

    public static LifecycleStateChange changed(
            LifecycleEntityType entityType,
            Long entityId,
            String fromState,
            String toState
    ) {
        return new LifecycleStateChange(entityType, String.valueOf(entityId), fromState, toState);
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 비어 있을 수 없습니다.");
        }
    }
}

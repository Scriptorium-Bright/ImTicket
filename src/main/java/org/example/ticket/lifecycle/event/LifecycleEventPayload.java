package org.example.ticket.lifecycle.event;

import java.util.List;
import java.util.Objects;

/** 사건 종류별 상태 변경 상세를 보존하는 JSON payload다. */
public record LifecycleEventPayload(
        List<Long> seatIds,
        List<LifecycleStateChange> stateChanges,
        String reasonCode
) {
    public LifecycleEventPayload {
        seatIds = List.copyOf(Objects.requireNonNull(seatIds, "seatIds는 필수입니다."));
        stateChanges = List.copyOf(Objects.requireNonNull(stateChanges, "stateChanges는 필수입니다."));
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode는 비어 있을 수 없습니다.");
        }
    }
}

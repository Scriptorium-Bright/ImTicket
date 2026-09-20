package org.example.ticket.lifecycle.event;

import java.util.Objects;

/** 하나의 업무 결정 안에서 저장할 Lifecycle 사건의 작성 전 표현이다. */
public record LifecycleEventDraft(
        LifecycleEventType eventType,
        LifecycleActorType actorType,
        Long paymentOrderId,
        Long paymentAttemptId,
        LifecycleEventPayload payload
) {
    public LifecycleEventDraft {
        Objects.requireNonNull(eventType, "eventType은 필수입니다.");
        Objects.requireNonNull(actorType, "actorType은 필수입니다.");
        Objects.requireNonNull(payload, "payload는 필수입니다.");
    }
}

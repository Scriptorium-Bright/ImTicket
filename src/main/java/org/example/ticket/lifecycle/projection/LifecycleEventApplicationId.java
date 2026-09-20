package org.example.ticket.lifecycle.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 조회 모델 버전과 사건 식별자를 묶은 적용 기록 키다. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LifecycleEventApplicationId {

    @Column(name = "projection_version")
    private int projectionVersion;

    @Column(name = "event_id")
    private String eventId;
}

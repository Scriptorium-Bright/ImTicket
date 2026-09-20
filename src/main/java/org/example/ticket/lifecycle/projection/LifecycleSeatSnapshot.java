package org.example.ticket.lifecycle.projection;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 조회 모델에서 예약에 연결된 좌석 한 건의 현재 상태다. */
@Entity
@Table(name = "lifecycle_seat_snapshot", indexes = {
        @Index(name = "idx_lifecycle_seat_snapshot_lifecycle", columnList = "projection_version, lifecycle_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LifecycleSeatSnapshot {

    @EmbeddedId
    private LifecycleSeatSnapshotId id;

    @Column(name = "seat_status", nullable = false, length = 30)
    private String seatStatus;

    private LifecycleSeatSnapshot(LifecycleSeatSnapshotId id, String seatStatus) {
        this.id = id;
        this.seatStatus = seatStatus;
    }

    public static LifecycleSeatSnapshot create(
            int projectionVersion,
            Long lifecycleId,
            Long seatId,
            String seatStatus
    ) {
        return new LifecycleSeatSnapshot(
                new LifecycleSeatSnapshotId(projectionVersion, lifecycleId, seatId),
                seatStatus
        );
    }

    public void setSeatStatus(String seatStatus) {
        this.seatStatus = seatStatus;
    }
}

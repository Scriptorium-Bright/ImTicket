package org.example.ticket.lifecycle.projection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 좌석별 Lifecycle 조회 모델 저장소다. */
public interface LifecycleSeatSnapshotRepository extends JpaRepository<LifecycleSeatSnapshot, LifecycleSeatSnapshotId> {

    List<LifecycleSeatSnapshot> findByIdProjectionVersionAndIdLifecycleId(
            int projectionVersion,
            Long lifecycleId
    );
}

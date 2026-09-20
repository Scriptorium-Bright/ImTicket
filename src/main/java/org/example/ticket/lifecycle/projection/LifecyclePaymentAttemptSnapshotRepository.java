package org.example.ticket.lifecycle.projection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 결제 시도별 Lifecycle 조회 모델 저장소다. */
public interface LifecyclePaymentAttemptSnapshotRepository
        extends JpaRepository<LifecyclePaymentAttemptSnapshot, LifecyclePaymentAttemptSnapshotId> {

    List<LifecyclePaymentAttemptSnapshot> findByIdProjectionVersionAndIdLifecycleId(
            int projectionVersion,
            Long lifecycleId
    );
}

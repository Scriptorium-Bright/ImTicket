package org.example.ticket.lifecycle.projection;

import org.springframework.data.jpa.repository.JpaRepository;

/** Lifecycle 요약 조회 모델 저장소다. */
public interface LifecycleSnapshotRepository extends JpaRepository<LifecycleSnapshot, LifecycleProjectionKey> {
}

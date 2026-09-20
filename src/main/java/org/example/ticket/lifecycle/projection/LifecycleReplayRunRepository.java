package org.example.ticket.lifecycle.projection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Replay 실행 기록 저장소다. */
public interface LifecycleReplayRunRepository extends JpaRepository<LifecycleReplayRun, String> {

    Optional<LifecycleReplayRun> findFirstByProjectionVersionAndLifecycleIdAndStatusOrderByRequestedAtDesc(
            int projectionVersion,
            Long lifecycleId,
            LifecycleReplayRunStatus status
    );
}

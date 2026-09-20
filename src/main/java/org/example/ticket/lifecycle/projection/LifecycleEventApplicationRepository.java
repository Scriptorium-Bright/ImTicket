package org.example.ticket.lifecycle.projection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** 사건별 조회 모델 적용 기록 저장소다. */
public interface LifecycleEventApplicationRepository
        extends JpaRepository<LifecycleEventApplication, LifecycleEventApplicationId> {

    @Query("""
            select application
            from LifecycleEventApplication application
            where application.id.projectionVersion = :projectionVersion
              and application.lifecycleId = :lifecycleId
              and application.decisionVersion = :decisionVersion
              and application.status = org.example.ticket.lifecycle.projection.LifecycleApplicationStatus.FAILED
            order by application.eventOrdinal asc
            """)
    List<LifecycleEventApplication> findFailedByDecision(
            int projectionVersion,
            Long lifecycleId,
            long decisionVersion
    );
}

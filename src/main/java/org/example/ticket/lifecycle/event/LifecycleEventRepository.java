package org.example.ticket.lifecycle.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** 사건 원본 조회는 Lifecycle별 논리 순번을 기준으로 수행한다. */
public interface LifecycleEventRepository extends JpaRepository<LifecycleEvent, Long> {

    List<LifecycleEvent> findByLifecycleIdOrderByDecisionVersionAscEventOrdinalAsc(Long lifecycleId);

    List<LifecycleEvent> findByLifecycleIdAndDecisionVersionOrderByEventOrdinalAsc(
            Long lifecycleId,
            Long decisionVersion
    );

    List<LifecycleEvent> findByPaymentOrderIdOrderByDecisionVersionAscEventOrdinalAsc(Long paymentOrderId);

    @Query("""
            select distinct event.decisionVersion
            from LifecycleEvent event
            where event.lifecycleId = :lifecycleId
            order by event.decisionVersion asc
            """)
    List<Long> findDecisionVersionsByLifecycleId(Long lifecycleId);

    @Query("""
            select new org.example.ticket.lifecycle.event.LifecycleEventDecisionKey(
                event.lifecycleId,
                event.decisionVersion
            )
            from LifecycleEvent event
            group by event.lifecycleId, event.decisionVersion
            order by min(event.recordedAt), min(event.id)
            """)
    List<LifecycleEventDecisionKey> findDecisionKeys(Pageable pageable);
}

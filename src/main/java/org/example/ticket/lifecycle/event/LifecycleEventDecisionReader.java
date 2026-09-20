package org.example.ticket.lifecycle.event;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Phase 3 Poller가 한 결정의 모든 사건을 함께 전달하기 위한 읽기 경계다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LifecycleEventDecisionReader {

    private final LifecycleEventRepository lifecycleEventRepository;

    /** 전역 자동 증가 키 체크포인트 없이 결정 단위 후보를 찾는다. */
    public List<LifecycleEventDecisionKey> findDecisionKeys(Pageable pageable) {
        return lifecycleEventRepository.findDecisionKeys(pageable);
    }

    /** 한 `(lifecycleId, decisionVersion)`의 사건 전체를 eventOrdinal 순서로 읽는다. */
    public List<LifecycleEvent> readDecision(LifecycleEventDecisionKey key) {
        return lifecycleEventRepository.findByLifecycleIdAndDecisionVersionOrderByEventOrdinalAsc(
                key.lifecycleId(),
                key.decisionVersion()
        );
    }
}

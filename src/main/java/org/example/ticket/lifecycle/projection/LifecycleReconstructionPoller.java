package org.example.ticket.lifecycle.projection;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.example.ticket.lifecycle.event.LifecycleEventDecisionKey;
import org.example.ticket.lifecycle.event.LifecycleEventDecisionReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 사건 결정 단위를 주기적으로 읽어 조회 모델 재구성기에 전달한다. */
@Component
@RequiredArgsConstructor
public class LifecycleReconstructionPoller {

    private final LifecycleEventDecisionReader decisionReader;
    private final LifecycleReconstructionService reconstructionService;

    @Value("${lifecycle.tracing.reconstruction.enabled:false}")
    private boolean enabled;

    @Value("${lifecycle.tracing.reconstruction.batch-size:100}")
    private int batchSize;

    /** 기본 비활성 상태로 배포하며 검증이 끝난 뒤 설정으로 활성화한다. */
    @Scheduled(fixedDelayString = "${lifecycle.tracing.reconstruction.poll-interval:1s}")
    @SchedulerLock(name = "reconstructLifecycleEvents", lockAtMostFor = "PT1M")
    public void poll() {
        if (!enabled) {
            return;
        }
        decisionReader.findDecisionKeys(PageRequest.of(0, batchSize)).stream()
                .forEach(this::reconstructDecision);
    }

    private void reconstructDecision(LifecycleEventDecisionKey key) {
        reconstructionService.reconstruct(key);
    }
}

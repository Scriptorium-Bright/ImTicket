package org.example.ticket.lifecycle.projection;

import lombok.RequiredArgsConstructor;
import org.example.ticket.lifecycle.event.LifecycleEventDecisionKey;
import org.example.ticket.lifecycle.event.LifecycleEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 사건 원본을 지정한 조회 모델 버전에 다시 적용한다. */
@Service
@RequiredArgsConstructor
public class LifecycleReplayService {

    private final LifecycleEventRepository eventRepository;
    private final LifecycleReplayRunRepository replayRunRepository;
    private final LifecycleSnapshotRepository snapshotRepository;
    private final LifecycleReconstructionService reconstructionService;

    @Transactional
    public LifecycleReplayResult replayLifecycle(Long lifecycleId, int targetProjectionVersion) {
        if (targetProjectionVersion <= 0) {
            throw new IllegalArgumentException("Replay 대상 조회 모델 버전은 양수여야 합니다.");
        }
        if (snapshotRepository.findById(new LifecycleProjectionKey(targetProjectionVersion, lifecycleId)).isPresent()) {
            throw new IllegalStateException("Replay 대상 조회 모델 버전이 이미 사용 중입니다: " + targetProjectionVersion);
        }
        replayRunRepository.findFirstByProjectionVersionAndLifecycleIdAndStatusOrderByRequestedAtDesc(
                        targetProjectionVersion,
                        lifecycleId,
                        LifecycleReplayRunStatus.RUNNING
                )
                .ifPresent(run -> {
                    throw new IllegalStateException("이미 실행 중인 Replay이 있습니다: " + run.getRunId());
                });

        List<Long> versions = eventRepository.findDecisionVersionsByLifecycleId(lifecycleId);
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("Replay할 사건이 없습니다: " + lifecycleId);
        }

        LifecycleReplayRun run = replayRunRepository.saveAndFlush(
                LifecycleReplayRun.start(UUID.randomUUID().toString(), targetProjectionVersion, lifecycleId)
        );
        try {
            for (Long version : versions) {
                LifecycleReconstructionResult result = reconstructionService.reconstruct(
                        targetProjectionVersion,
                        new LifecycleEventDecisionKey(lifecycleId, version)
                );
                run.recordProcessedEvent(result.eventCount());
                if (result.applicationStatus() == LifecycleApplicationStatus.FAILED) {
                    run.recordFailure();
                }
            }
            if (run.getFailedEvents() > 0) {
                run.fail("계약 오류 사건이 있어 Replay 결과가 불완전합니다.");
            } else {
                run.complete();
            }
        } catch (RuntimeException exception) {
            run.recordFailure();
            run.fail(exception.getMessage());
        }
        replayRunRepository.save(run);
        return new LifecycleReplayResult(
                run.getRunId(),
                lifecycleId,
                targetProjectionVersion,
                run.getStatus(),
                run.getProcessedEvents(),
                run.getFailedEvents()
        );
    }
}

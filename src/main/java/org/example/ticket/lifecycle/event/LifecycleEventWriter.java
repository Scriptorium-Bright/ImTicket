package org.example.ticket.lifecycle.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 업무 상태 변경과 같은 트랜잭션에서 Lifecycle 사건을 기록한다. */
@Service
@RequiredArgsConstructor
public class LifecycleEventWriter {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Object COMMIT_CONTEXT_RESOURCE_KEY = LifecycleEventWriter.class.getName() + ".commitContext";

    private final LifecycleEventRepository lifecycleEventRepository;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    @Value("${lifecycle.tracing.event-writer.enabled:false}")
    private boolean enabled;

    /** 신규 예약의 첫 사건을 기록하고 Lifecycle 순번을 1로 시작한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReservationCreated(Reservation reservation, LifecycleEventDraft draft) {
        if (!enabled) {
            return;
        }
        long decisionVersion = reservation.startLifecycleTracking();
        persistDecision(reservation, decisionVersion, List.of(draft));
    }

    /** 기존에 추적을 시작한 예약의 다음 업무 결정을 사건으로 남긴다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDecision(Reservation reservation, List<LifecycleEventDraft> drafts) {
        if (!enabled || drafts.isEmpty()) {
            return;
        }
        if (!reservation.isLifecycleTracked()) {
            registerAfterCommit(() -> incrementCounter("imticket.lifecycle.events.legacy.skipped", drafts.size()));
            return;
        }
        long decisionVersion = reservation.advanceLifecycleVersion();
        persistDecision(reservation, decisionVersion, drafts);
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void persistDecision(
            Reservation reservation,
            long decisionVersion,
            List<LifecycleEventDraft> drafts
    ) {
        requireReservationId(reservation);
        CommitContext commitContext = commitContext();
        List<LifecycleEvent> events = new ArrayList<>(drafts.size());

        for (int ordinal = 0; ordinal < drafts.size(); ordinal++) {
            LifecycleEventDraft draft = drafts.get(ordinal);
            events.add(LifecycleEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(draft.eventType().wireValue())
                    .schemaVersion(CURRENT_SCHEMA_VERSION)
                    .lifecycleId(reservation.getId())
                    .paymentOrderId(draft.paymentOrderId())
                    .paymentAttemptId(draft.paymentAttemptId())
                    .decisionVersion(decisionVersion)
                    .eventOrdinal(ordinal)
                    .commitGroupId(commitContext.commitGroupId())
                    .actorType(draft.actorType().name())
                    .occurredAt(commitContext.occurredAt())
                    .payload(encode(draft.payload()))
                    .build());
        }

        lifecycleEventRepository.saveAll(events);
        registerAfterCommit(() -> {
            for (LifecycleEvent event : events) {
                incrementCounter("imticket.lifecycle.events.committed", event.getEventType());
            }
        });
    }

    private String encode(LifecycleEventPayload payload) {
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            incrementCounter("imticket.lifecycle.events.serialization.failures");
            throw new IllegalStateException("Lifecycle 사건 payload 직렬화에 실패했습니다.", exception);
        }
    }

    private void requireReservationId(Reservation reservation) {
        if (reservation.getId() == null) {
            throw new IllegalStateException("Lifecycle 사건은 저장된 Reservation에만 기록할 수 있습니다.");
        }
    }

    private CommitContext commitContext() {
        CommitContext existing = (CommitContext) TransactionSynchronizationManager.getResource(
                COMMIT_CONTEXT_RESOURCE_KEY
        );
        if (existing != null) {
            return existing;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Lifecycle 사건 기록에는 활성 트랜잭션 동기화가 필요합니다.");
        }
        CommitContext created = new CommitContext(UUID.randomUUID().toString(), LocalDateTime.now());
        TransactionSynchronizationManager.bindResource(COMMIT_CONTEXT_RESOURCE_KEY, created);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(COMMIT_CONTEXT_RESOURCE_KEY);
            }
        });
        return created;
    }

    private void registerAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Lifecycle 사건 기록에는 활성 트랜잭션 동기화가 필요합니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void incrementCounter(String name) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter(name).increment();
        }
    }

    private void incrementCounter(String name, String eventType) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter(name, "event_type", eventType).increment();
        }
    }

    private void incrementCounter(String name, int count) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter(name).increment(count);
        }
    }

    private record CommitContext(String commitGroupId, LocalDateTime occurredAt) {
    }
}

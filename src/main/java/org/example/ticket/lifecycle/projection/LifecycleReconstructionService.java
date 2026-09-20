package org.example.ticket.lifecycle.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ticket.lifecycle.event.LifecycleActorType;
import org.example.ticket.lifecycle.event.LifecycleEntityType;
import org.example.ticket.lifecycle.event.LifecycleEvent;
import org.example.ticket.lifecycle.event.LifecycleEventDecisionKey;
import org.example.ticket.lifecycle.event.LifecycleEventDecisionReader;
import org.example.ticket.lifecycle.event.LifecycleEventPayload;
import org.example.ticket.lifecycle.event.LifecycleEventRepository;
import org.example.ticket.lifecycle.event.LifecycleEventType;
import org.example.ticket.lifecycle.event.LifecycleStateChange;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 커밋 사건을 결정 버전 순서로 조회 모델에 적용하는 멱등 재구성기다. */
@Service
@RequiredArgsConstructor
public class LifecycleReconstructionService {

    public static final int CURRENT_PROJECTION_VERSION = 1;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LifecycleEventDecisionReader decisionReader;
    private final LifecycleEventRepository eventRepository;
    private final LifecycleSnapshotRepository snapshotRepository;
    private final LifecycleSeatSnapshotRepository seatSnapshotRepository;
    private final LifecyclePaymentAttemptSnapshotRepository paymentAttemptSnapshotRepository;
    private final LifecycleEventApplicationRepository applicationRepository;

    /** 한 `(lifecycleId, decisionVersion)` 결정을 현재 조회 모델에 적용한다. */
    @Transactional
    public LifecycleReconstructionResult reconstruct(LifecycleEventDecisionKey key) {
        return reconstruct(CURRENT_PROJECTION_VERSION, key);
    }

    /** 지정한 조회 모델 버전에 한 결정을 적용한다. Replay은 이 경계를 사용한다. */
    @Transactional
    public LifecycleReconstructionResult reconstruct(
            int projectionVersion,
            LifecycleEventDecisionKey key
    ) {
        return reconstruct(projectionVersion, key, false);
    }

    /** 계약 오류로 보류한 결정을 운영자가 다시 적용할 때 사용하는 경계다. */
    @Transactional
    public LifecycleReconstructionResult retryFailed(
            int projectionVersion,
            LifecycleEventDecisionKey key
    ) {
        return reconstruct(projectionVersion, key, true);
    }

    private LifecycleReconstructionResult reconstruct(
            int projectionVersion,
            LifecycleEventDecisionKey key,
            boolean forceRetry
    ) {
        List<LifecycleEvent> events = decisionReader.readDecision(key);
        if (events.isEmpty()) {
            throw new IllegalArgumentException("재구성할 사건이 없습니다: " + key);
        }

        LifecycleSnapshot snapshot = snapshotRepository.findById(snapshotKey(projectionVersion, key.lifecycleId()))
                .orElseGet(() -> LifecycleSnapshot.empty(projectionVersion, key.lifecycleId()));

        if (!forceRetry) {
            List<LifecycleEventApplication> failedApplications = applicationRepository.findFailedByDecision(
                    projectionVersion,
                    key.lifecycleId(),
                    key.decisionVersion()
            );
            if (!failedApplications.isEmpty()) {
                return result(key, events.size(), LifecycleApplicationStatus.FAILED, snapshot);
            }
        }

        if (key.decisionVersion() > snapshot.getLastAppliedVersion() + 1) {
            snapshot.setTrustStatus(LifecycleTrustStatus.PROCESSING);
            snapshotRepository.save(snapshot);
            events.forEach(event -> pendingApplication(projectionVersion, key, event));
            return result(key, events.size(), LifecycleApplicationStatus.PENDING, snapshot);
        }

        if (key.decisionVersion() <= snapshot.getLastAppliedVersion()) {
            events.forEach(event -> duplicateApplication(projectionVersion, key, event));
            return result(key, events.size(), LifecycleApplicationStatus.DUPLICATE, snapshot);
        }

        try {
            validateDecision(key, events);
            MutableState state = loadState(snapshot, events);
            for (LifecycleEvent event : events) {
                applyEvent(state, event);
            }
            materializeState(snapshot, state);
            snapshot.setLastAppliedVersion(key.decisionVersion());
        } catch (IllegalStateException exception) {
            snapshot.setTrustStatus(LifecycleTrustStatus.MISMATCH);
            snapshotRepository.save(snapshot);
            events.forEach(event -> failedApplication(
                    projectionVersion,
                    key,
                    event,
                    "CONTRACT_EVENT"
            ));
            return result(key, events.size(), LifecycleApplicationStatus.FAILED, snapshot);
        }
        snapshot.setPathClassification(classifyPath(
                eventRepository.findByLifecycleIdOrderByDecisionVersionAscEventOrdinalAsc(key.lifecycleId())
        ));
        snapshot.setTrustStatus(LifecycleTrustStatus.PROCESSING);
        snapshotRepository.save(snapshot);

        events.forEach(event -> appliedApplication(projectionVersion, key, event));
        return result(key, events.size(), LifecycleApplicationStatus.APPLIED, snapshot);
    }

    private LifecycleProjectionKey snapshotKey(int projectionVersion, Long lifecycleId) {
        return new LifecycleProjectionKey(projectionVersion, lifecycleId);
    }

    private LifecycleReconstructionResult result(
            LifecycleEventDecisionKey key,
            int eventCount,
            LifecycleApplicationStatus status,
            LifecycleSnapshot snapshot
    ) {
        return new LifecycleReconstructionResult(
                key.lifecycleId(),
                key.decisionVersion(),
                eventCount,
                status,
                snapshot.getPathClassification(),
                snapshot.getTrustStatus()
        );
    }

    private void validateDecision(LifecycleEventDecisionKey key, List<LifecycleEvent> events) {
        String commitGroupId = events.getFirst().getCommitGroupId();
        for (int ordinal = 0; ordinal < events.size(); ordinal++) {
            LifecycleEvent event = events.get(ordinal);
            if (!key.lifecycleId().equals(event.getLifecycleId())
                    || key.decisionVersion() != event.getDecisionVersion()
                    || !commitGroupId.equals(event.getCommitGroupId())
                    || event.getEventOrdinal() != ordinal
                    || event.getSchemaVersion() != 1) {
                throw new IllegalStateException("Lifecycle 결정 계약을 만족하지 않습니다: " + key);
            }
            parseEventType(event);
            decode(event);
        }
    }

    private MutableState loadState(LifecycleSnapshot snapshot, List<LifecycleEvent> events) {
        MutableState state = new MutableState(
                snapshot.getReservationStatus(),
                snapshot.getPaymentOrderStatus(),
                snapshot.getPaymentOrderId()
        );
        for (LifecycleEvent event : events) {
            LifecycleEventPayload payload = decode(event);
            for (LifecycleStateChange change : payload.stateChanges()) {
                Long entityId = parseId(change.entityId());
                if (change.entityType() == LifecycleEntityType.SEAT) {
                    if (!state.seats.containsKey(entityId)) {
                        state.seats.put(
                                entityId,
                                seatSnapshotRepository.findById(new LifecycleSeatSnapshotId(
                                                snapshot.getId().getProjectionVersion(),
                                                snapshot.getId().getLifecycleId(),
                                                entityId
                                        ))
                                        .map(LifecycleSeatSnapshot::getSeatStatus)
                                        .orElse("AVAILABLE")
                        );
                    }
                } else if (change.entityType() == LifecycleEntityType.PAYMENT_ATTEMPT) {
                    if (!state.paymentAttempts.containsKey(entityId)) {
                        state.paymentAttempts.put(
                                entityId,
                                paymentAttemptSnapshotRepository.findById(new LifecyclePaymentAttemptSnapshotId(
                                                snapshot.getId().getProjectionVersion(),
                                                snapshot.getId().getLifecycleId(),
                                                entityId
                                        ))
                                        .map(LifecyclePaymentAttemptSnapshot::getPaymentAttemptStatus)
                                        .orElse(null)
                        );
                    }
                }
            }
        }
        return state;
    }

    private void applyEvent(MutableState state, LifecycleEvent event) {
        LifecycleEventType eventType = parseEventType(event);
        LifecycleEventPayload payload = decode(event);
        validateSeatIds(payload);
        for (LifecycleStateChange change : payload.stateChanges()) {
            Long entityId = parseId(change.entityId());
            String current = state.current(change.entityType(), entityId);
            if (!equalsNullable(current, change.fromState())
                    || !allowedTransition(eventType, change.entityType(), change.fromState(), change.toState())) {
                throw new IllegalStateException("상태 전이를 적용할 수 없습니다: " + event.getEventId());
            }
            state.change(change.entityType(), entityId, change.toState());
            if (change.entityType() == LifecycleEntityType.PAYMENT_ORDER) {
                state.paymentOrderId = event.getPaymentOrderId() == null
                        ? entityId
                        : event.getPaymentOrderId();
            }
        }
        if (event.getPaymentOrderId() != null) {
            state.paymentOrderId = event.getPaymentOrderId();
        }
    }

    private void materializeState(LifecycleSnapshot snapshot, MutableState state) {
        snapshot.setReservationStatus(state.reservationStatus);
        snapshot.setPaymentOrderStatus(state.paymentOrderStatus);
        snapshot.setPaymentOrderId(state.paymentOrderId);

        state.seats.forEach((seatId, seatStatus) -> {
            LifecycleSeatSnapshotId id = new LifecycleSeatSnapshotId(
                    snapshot.getId().getProjectionVersion(),
                    snapshot.getId().getLifecycleId(),
                    seatId
            );
            LifecycleSeatSnapshot seat = seatSnapshotRepository.findById(id)
                    .orElseGet(() -> LifecycleSeatSnapshot.create(
                            snapshot.getId().getProjectionVersion(),
                            snapshot.getId().getLifecycleId(),
                            seatId,
                            seatStatus
                    ));
            seat.setSeatStatus(seatStatus);
            seatSnapshotRepository.save(seat);
        });

        state.paymentAttempts.forEach((attemptId, attemptStatus) -> {
            LifecyclePaymentAttemptSnapshotId id = new LifecyclePaymentAttemptSnapshotId(
                    snapshot.getId().getProjectionVersion(),
                    snapshot.getId().getLifecycleId(),
                    attemptId
            );
            LifecyclePaymentAttemptSnapshot attempt = paymentAttemptSnapshotRepository.findById(id)
                    .orElseGet(() -> LifecyclePaymentAttemptSnapshot.create(
                            snapshot.getId().getProjectionVersion(),
                            snapshot.getId().getLifecycleId(),
                            attemptId,
                            attemptStatus
                    ));
            attempt.setPaymentAttemptStatus(attemptStatus);
            paymentAttemptSnapshotRepository.save(attempt);
        });
    }

    private void validateSeatIds(LifecycleEventPayload payload) {
        Set<Long> listed = new LinkedHashSet<>(payload.seatIds());
        Set<Long> changed = payload.stateChanges().stream()
                .filter(change -> change.entityType() == LifecycleEntityType.SEAT)
                .map(change -> parseId(change.entityId()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!listed.isEmpty() && !listed.equals(changed)) {
            throw new IllegalStateException("사건의 seatIds와 좌석 상태 변경 대상이 다릅니다.");
        }
    }

    private boolean allowedTransition(
            LifecycleEventType eventType,
            LifecycleEntityType entityType,
            String fromState,
            String toState
    ) {
        return switch (eventType) {
            case RESERVATION_CREATED -> entityType == LifecycleEntityType.RESERVATION
                    ? transition(fromState, toState, null, "PENDING_PAYMENT")
                    : entityType == LifecycleEntityType.SEAT
                    && transition(fromState, toState, "AVAILABLE", "LOCKED");
            case PAYMENT_PREPARED -> entityType == LifecycleEntityType.PAYMENT_ORDER
                    ? transition(fromState, toState, null, "READY")
                    : entityType == LifecycleEntityType.PAYMENT_ATTEMPT
                    && transition(fromState, toState, null, "READY");
            case PAYMENT_APPROVED -> entityType == LifecycleEntityType.PAYMENT_ATTEMPT
                    && transition(fromState, toState, "READY", "PAID");
            case RESERVATION_COMPLETED -> switch (entityType) {
                case RESERVATION -> transition(fromState, toState, "PENDING_PAYMENT", "SUCCESS");
                case SEAT -> transition(fromState, toState, "LOCKED", "RESERVED");
                case PAYMENT_ORDER -> transition(fromState, toState, "READY", "APPLIED");
                default -> false;
            };
            case RESERVATION_EXPIRED -> entityType == LifecycleEntityType.RESERVATION
                    ? transition(fromState, toState, "PENDING_PAYMENT", "EXPIRED")
                    : entityType == LifecycleEntityType.SEAT
                    && transition(fromState, toState, "LOCKED", "AVAILABLE");
            case PAYMENT_REFUND_PENDING -> entityType == LifecycleEntityType.PAYMENT_ORDER
                    && transition(fromState, toState, "READY", "REFUND_PENDING");
        };
    }

    private boolean transition(
            String fromState,
            String toState,
            String expectedFromState,
            String expectedToState
    ) {
        return equalsNullable(fromState, expectedFromState)
                && equalsNullable(toState, expectedToState);
    }

    private LifecyclePathClassification classifyPath(List<LifecycleEvent> events) {
        Map<Long, List<LifecycleEvent>> byVersion = new HashMap<>();
        events.forEach(event -> byVersion.computeIfAbsent(event.getDecisionVersion(), ignored -> new ArrayList<>())
                .add(event));

        boolean paymentHandlerExpired = byVersion.values().stream().anyMatch(group ->
                hasType(group, LifecycleEventType.PAYMENT_APPROVED)
                        && hasType(group, LifecycleEventType.RESERVATION_EXPIRED)
                        && hasType(group, LifecycleEventType.PAYMENT_REFUND_PENDING)
                        && group.stream().anyMatch(event ->
                        event.getEventType().equals(LifecycleEventType.RESERVATION_EXPIRED.wireValue())
                                && event.getActorType().equals(LifecycleActorType.PAYMENT_VERIFICATION.name()))
        );
        if (paymentHandlerExpired) {
            return LifecyclePathClassification.PAYMENT_HANDLER_EXPIRED;
        }

        Long schedulerExpirationVersion = events.stream()
                .filter(event -> event.getEventType().equals(LifecycleEventType.RESERVATION_EXPIRED.wireValue()))
                .filter(event -> event.getActorType().equals(LifecycleActorType.EXPIRATION_SCHEDULER.name()))
                .map(LifecycleEvent::getDecisionVersion)
                .min(Comparator.naturalOrder())
                .orElse(null);
        boolean approvedAfterExpiration = schedulerExpirationVersion != null && events.stream().anyMatch(event ->
                event.getDecisionVersion() > schedulerExpirationVersion
                        && event.getEventType().equals(LifecycleEventType.PAYMENT_APPROVED.wireValue())
        );
        boolean refundAfterExpiration = schedulerExpirationVersion != null && events.stream().anyMatch(event ->
                event.getDecisionVersion() > schedulerExpirationVersion
                        && event.getEventType().equals(LifecycleEventType.PAYMENT_REFUND_PENDING.wireValue())
        );
        if (approvedAfterExpiration && refundAfterExpiration) {
            return LifecyclePathClassification.EXPIRATION_FIRST;
        }

        boolean completed = events.stream().anyMatch(event ->
                event.getEventType().equals(LifecycleEventType.RESERVATION_COMPLETED.wireValue()));
        if (completed) {
            return LifecyclePathClassification.NORMAL_COMPLETED;
        }
        if (schedulerExpirationVersion != null
                && events.stream().noneMatch(event ->
                event.getEventType().equals(LifecycleEventType.PAYMENT_APPROVED.wireValue()))) {
            return LifecyclePathClassification.EXPIRED_WITHOUT_PAYMENT;
        }
        return LifecyclePathClassification.IN_PROGRESS;
    }

    private boolean hasType(List<LifecycleEvent> events, LifecycleEventType eventType) {
        return events.stream().anyMatch(event -> event.getEventType().equals(eventType.wireValue()));
    }

    private LifecycleEventType parseEventType(LifecycleEvent event) {
        for (LifecycleEventType type : LifecycleEventType.values()) {
            if (type.wireValue().equals(event.getEventType())) {
                return type;
            }
        }
        throw new IllegalStateException("알 수 없는 Lifecycle 사건 종류: " + event.getEventType());
    }

    private LifecycleEventPayload decode(LifecycleEvent event) {
        try {
            return OBJECT_MAPPER.readValue(event.getPayload(), LifecycleEventPayload.class);
        } catch (JsonProcessingException exception) {
            try {
                String nestedJson = OBJECT_MAPPER.readValue(event.getPayload(), String.class);
                return OBJECT_MAPPER.readValue(nestedJson, LifecycleEventPayload.class);
            } catch (JsonProcessingException nestedException) {
                nestedException.addSuppressed(exception);
                throw new IllegalStateException(
                        "Lifecycle 사건 payload를 읽을 수 없습니다: " + event.getEventId(),
                        nestedException
                );
            }
        }
    }

    private Long parseId(String entityId) {
        try {
            return Long.valueOf(entityId);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("상태 변경 식별자가 숫자가 아닙니다: " + entityId, exception);
        }
    }

    private boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private void pendingApplication(
            int projectionVersion,
            LifecycleEventDecisionKey key,
            LifecycleEvent event
    ) {
        LifecycleEventApplicationId id = new LifecycleEventApplicationId(
                projectionVersion,
                event.getEventId()
        );
        LifecycleEventApplication application = applicationRepository.findById(id)
                .orElseGet(() -> LifecycleEventApplication.pending(
                        projectionVersion,
                        event.getEventId(),
                        key.lifecycleId(),
                        key.decisionVersion(),
                        event.getEventOrdinal()
                ));
        if (application.getStatus() != LifecycleApplicationStatus.APPLIED) {
            applicationRepository.save(application);
        }
    }

    private void appliedApplication(
            int projectionVersion,
            LifecycleEventDecisionKey key,
            LifecycleEvent event
    ) {
        LifecycleEventApplicationId id = new LifecycleEventApplicationId(
                projectionVersion,
                event.getEventId()
        );
        LifecycleEventApplication application = applicationRepository.findById(id)
                .orElseGet(() -> LifecycleEventApplication.pending(
                        projectionVersion,
                        event.getEventId(),
                        key.lifecycleId(),
                        key.decisionVersion(),
                        event.getEventOrdinal()
                ));
        application.markApplied();
        applicationRepository.save(application);
    }

    private void duplicateApplication(
            int projectionVersion,
            LifecycleEventDecisionKey key,
            LifecycleEvent event
    ) {
        LifecycleEventApplicationId id = new LifecycleEventApplicationId(
                projectionVersion,
                event.getEventId()
        );
        LifecycleEventApplication application = applicationRepository.findById(id)
                .orElseGet(() -> LifecycleEventApplication.pending(
                        projectionVersion,
                        event.getEventId(),
                        key.lifecycleId(),
                        key.decisionVersion(),
                        event.getEventOrdinal()
                ));
        if (application.getStatus() != LifecycleApplicationStatus.APPLIED) {
            application.markDuplicate();
            applicationRepository.save(application);
        }
    }

    private void failedApplication(
            int projectionVersion,
            LifecycleEventDecisionKey key,
            LifecycleEvent event,
            String errorCode
    ) {
        LifecycleEventApplicationId id = new LifecycleEventApplicationId(
                projectionVersion,
                event.getEventId()
        );
        LifecycleEventApplication application = applicationRepository.findById(id)
                .orElseGet(() -> LifecycleEventApplication.pending(
                        projectionVersion,
                        event.getEventId(),
                        key.lifecycleId(),
                        key.decisionVersion(),
                        event.getEventOrdinal()
                ));
        application.markFailed(errorCode, LifecycleApplicationErrorType.CONTRACT, null);
        applicationRepository.save(application);
    }

    private static final class MutableState {
        private String reservationStatus;
        private String paymentOrderStatus;
        private Long paymentOrderId;
        private final Map<Long, String> seats = new HashMap<>();
        private final Map<Long, String> paymentAttempts = new HashMap<>();

        private MutableState(String reservationStatus, String paymentOrderStatus, Long paymentOrderId) {
            this.reservationStatus = reservationStatus;
            this.paymentOrderStatus = paymentOrderStatus;
            this.paymentOrderId = paymentOrderId;
        }

        private String current(LifecycleEntityType entityType, Long entityId) {
            return switch (entityType) {
                case RESERVATION -> reservationStatus;
                case SEAT -> seats.get(entityId);
                case PAYMENT_ORDER -> paymentOrderStatus;
                case PAYMENT_ATTEMPT -> paymentAttempts.get(entityId);
            };
        }

        private void change(LifecycleEntityType entityType, Long entityId, String state) {
            switch (entityType) {
                case RESERVATION -> reservationStatus = state;
                case SEAT -> seats.put(entityId, state);
                case PAYMENT_ORDER -> paymentOrderStatus = state;
                case PAYMENT_ATTEMPT -> paymentAttempts.put(entityId, state);
            }
        }
    }
}

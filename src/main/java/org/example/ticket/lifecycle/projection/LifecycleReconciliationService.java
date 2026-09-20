package org.example.ticket.lifecycle.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ticket.lifecycle.event.LifecycleEvent;
import org.example.ticket.lifecycle.event.LifecycleEventRepository;
import org.example.ticket.payment.model.PaymentAttempt;
import org.example.ticket.payment.model.PaymentOrder;
import org.example.ticket.payment.repository.PaymentAttemptRepository;
import org.example.ticket.payment.repository.PaymentOrderRepository;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.domain.ReservedSeat;
import org.example.ticket.reservation.booking.repository.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 원천 MySQL 상태, 사건 범위, 조회 모델을 한 예약 단위로 비교한다. */
@Service
@RequiredArgsConstructor
public class LifecycleReconciliationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReservationRepository reservationRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final LifecycleEventRepository eventRepository;
    private final LifecycleSnapshotRepository snapshotRepository;
    private final LifecycleSeatSnapshotRepository seatSnapshotRepository;
    private final LifecyclePaymentAttemptSnapshotRepository paymentAttemptSnapshotRepository;

    @Transactional
    public LifecycleReconciliationResult reconcile(int projectionVersion, Long lifecycleId) {
        Reservation reservation = reservationRepository.findByIdWithSeats(lifecycleId)
                .orElseThrow(() -> new IllegalArgumentException("예약을 찾을 수 없습니다: " + lifecycleId));
        PaymentOrder paymentOrder = paymentOrderRepository.findByReservation_Id(lifecycleId).orElse(null);
        List<PaymentAttempt> paymentAttempts = paymentOrder == null
                ? List.of()
                : paymentAttemptRepository.findByPaymentOrderIdOrderByIdAsc(paymentOrder.getId());
        List<LifecycleEvent> events = eventRepository.findByLifecycleIdOrderByDecisionVersionAscEventOrdinalAsc(lifecycleId);

        long sourceVersion = reservation.getLifecycleVersion() == null ? 0L : reservation.getLifecycleVersion();
        long eventVersion = contiguousEventVersion(events);
        List<Long> missingVersions = missingVersions(sourceVersion, events);

        LifecycleSnapshot snapshot = snapshotRepository.findById(new LifecycleProjectionKey(projectionVersion, lifecycleId))
                .orElseGet(() -> LifecycleSnapshot.empty(projectionVersion, lifecycleId));
        List<LifecycleSeatSnapshot> seatSnapshots = seatSnapshotRepository
                .findByIdProjectionVersionAndIdLifecycleId(projectionVersion, lifecycleId);
        List<LifecyclePaymentAttemptSnapshot> attemptSnapshots = paymentAttemptSnapshotRepository
                .findByIdProjectionVersionAndIdLifecycleId(projectionVersion, lifecycleId);

        Map<String, Object> differences = new LinkedHashMap<>();
        compareValue(differences, "reservationStatus", name(reservation.getReservationStatus()), snapshot.getReservationStatus());
        compareValue(differences, "paymentOrderId", id(paymentOrder), snapshot.getPaymentOrderId());
        compareValue(differences, "paymentOrderStatus", name(paymentOrder == null ? null : paymentOrder.getStatus()),
                snapshot.getPaymentOrderStatus());

        Map<Long, String> expectedSeats = reservation.getReservedSeats().stream()
                .map(ReservedSeat::getSeat)
                .collect(Collectors.toMap(
                        seat -> seat.getId(),
                        seat -> name(seat.getSeatStatus()),
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        Map<Long, String> actualSeats = seatSnapshots.stream()
                .collect(Collectors.toMap(
                        item -> item.getId().getSeatId(),
                        LifecycleSeatSnapshot::getSeatStatus,
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        compareMap(differences, "seatStatuses", expectedSeats, actualSeats);

        Map<Long, String> expectedAttempts = paymentAttempts.stream()
                .collect(Collectors.toMap(
                        PaymentAttempt::getId,
                        attempt -> name(attempt.getStatus()),
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        Map<Long, String> actualAttempts = attemptSnapshots.stream()
                .collect(Collectors.toMap(
                        item -> item.getId().getPaymentAttemptId(),
                        LifecyclePaymentAttemptSnapshot::getPaymentAttemptStatus,
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        compareMap(differences, "paymentAttemptStatuses", expectedAttempts, actualAttempts);

        if (!missingVersions.isEmpty()) {
            differences.put("missingDecisionVersions", missingVersions);
        }
        if (eventVersion > sourceVersion) {
            differences.put("eventVersionExceedsSourceVersion", Map.of(
                    "sourceVersion", sourceVersion,
                    "eventVersion", eventVersion
            ));
        }

        boolean snapshotMissing = snapshot.getCreatedAt() == null;
        LifecycleTrustStatus trustStatus = determineTrust(
                snapshotMissing,
                snapshot,
                sourceVersion,
                eventVersion,
                missingVersions,
                differences
        );
        snapshot.markReconciled(
                sourceVersion,
                eventVersion,
                serialize(differences),
                trustStatus
        );
        snapshotRepository.save(snapshot);
        return new LifecycleReconciliationResult(
                lifecycleId,
                projectionVersion,
                sourceVersion,
                eventVersion,
                trustStatus,
                differences
        );
    }

    private LifecycleTrustStatus determineTrust(
            boolean snapshotMissing,
            LifecycleSnapshot snapshot,
            long sourceVersion,
            long eventVersion,
            List<Long> missingVersions,
            Map<String, Object> differences
    ) {
        if (snapshotMissing || !missingVersions.isEmpty()) {
            return LifecycleTrustStatus.INCOMPLETE;
        }
        if (snapshot.getLastAppliedVersion() < eventVersion || eventVersion < sourceVersion) {
            return LifecycleTrustStatus.PROCESSING;
        }
        return differences.isEmpty() ? LifecycleTrustStatus.CONSISTENT : LifecycleTrustStatus.MISMATCH;
    }

    private List<Long> missingVersions(long sourceVersion, List<LifecycleEvent> events) {
        Set<Long> present = events.stream()
                .map(LifecycleEvent::getDecisionVersion)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Long> missing = new ArrayList<>();
        for (long version = 1; version <= sourceVersion; version++) {
            if (!present.contains(version)) {
                missing.add(version);
            }
        }
        return missing;
    }

    private long contiguousEventVersion(List<LifecycleEvent> events) {
        Set<Long> present = events.stream()
                .map(LifecycleEvent::getDecisionVersion)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        long version = 0L;
        while (present.contains(version + 1)) {
            version++;
        }
        return version;
    }

    private void compareValue(Map<String, Object> differences, String field, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            differences.put(field, pair(expected, actual));
        }
    }

    private <K, V> void compareMap(
            Map<String, Object> differences,
            String field,
            Map<K, V> expected,
            Map<K, V> actual
    ) {
        if (!expected.equals(actual)) {
            differences.put(field, pair(expected, actual));
        }
    }

    private Map<String, Object> pair(Object expected, Object actual) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("source", expected);
        values.put("projection", actual);
        return values;
    }

    private Long id(PaymentOrder paymentOrder) {
        return paymentOrder == null ? null : paymentOrder.getId();
    }

    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private String serialize(Map<String, Object> differences) {
        try {
            return OBJECT_MAPPER.writeValueAsString(differences);
        } catch (JsonProcessingException exception) {
            return "{\"serializationError\":\"true\"}";
        }
    }
}

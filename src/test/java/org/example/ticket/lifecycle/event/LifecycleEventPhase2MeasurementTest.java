package org.example.ticket.lifecycle.event;

import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.repository.ReservationRepository;
import org.example.ticket.util.constant.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2에서 추가한 사건 기록 비용과 시나리오별 사건량을 같은 MySQL 조건에서 측정한다.
 * 이 시험의 수치는 제품 SLO가 아니라 Phase 3 진입 전 구현 비용 기준선으로 사용한다.
 */
@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "lifecycle.tracing.event-writer.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LifecycleEventWriter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LifecycleEventPhase2MeasurementTest {

    private static final int SAMPLE_COUNT = 60;
    private static final int WARMUP_COUNT = 5;

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
            .withDatabaseName("imticket_lifecycle_measurement")
            .withUsername("imticket")
            .withPassword("imticket-test");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private LifecycleEventRepository lifecycleEventRepository;

    @Autowired
    private LifecycleEventWriter lifecycleEventWriter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void measuresTransactionCostAndEventCardinality() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        ReflectionTestUtils.setField(lifecycleEventWriter, "enabled", false);
        measure(transaction, WARMUP_COUNT, 0, false);
        List<Long> writerDisabled = measure(transaction, SAMPLE_COUNT, 0, false);

        ReflectionTestUtils.setField(lifecycleEventWriter, "enabled", true);
        measure(transaction, WARMUP_COUNT, 4, true);
        List<Long> normalLifecycle = measure(transaction, SAMPLE_COUNT, 4, true);
        List<Long> expiredApprovalLifecycle = measure(transaction, SAMPLE_COUNT, 5, true);

        long expectedMeasuredEvents = (long) SAMPLE_COUNT * (4 + 5);
        long actualEvents = lifecycleEventRepository.count();
        assertThat(actualEvents).isEqualTo((long) WARMUP_COUNT * 4 + expectedMeasuredEvents);

        System.out.println("phase2.lifecycle.event.measurement");
        System.out.println("database=mysql-8.0");
        System.out.println("samples=" + SAMPLE_COUNT);
        print("writer_disabled", writerDisabled);
        print("normal_4_events", normalLifecycle);
        print("expired_approval_5_events", expiredApprovalLifecycle);
        System.out.println("event_count=" + actualEvents);
        System.out.println("event_count_per_normal_lifecycle=4");
        System.out.println("event_count_per_expired_approval_lifecycle=5");
    }

    private List<Long> measure(
            TransactionTemplate transaction,
            int count,
            int eventCount,
            boolean writerEnabled
    ) {
        List<Long> durations = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long started = System.nanoTime();
            transaction.executeWithoutResult(status -> {
                Reservation reservation = reservationRepository.saveAndFlush(newReservation());
                if (!writerEnabled) {
                    return;
                }
                lifecycleEventWriter.recordReservationCreated(reservation, draft(LifecycleEventType.RESERVATION_CREATED));
                lifecycleEventWriter.recordDecision(
                        reservation,
                        List.of(draft(LifecycleEventType.PAYMENT_PREPARED))
                );
                if (eventCount == 4) {
                    lifecycleEventWriter.recordDecision(
                            reservation,
                            List.of(
                                    draft(LifecycleEventType.PAYMENT_APPROVED),
                                    draft(LifecycleEventType.RESERVATION_COMPLETED)
                            )
                    );
                } else if (eventCount == 5) {
                    lifecycleEventWriter.recordDecision(
                            reservation,
                            List.of(
                                    draft(LifecycleEventType.PAYMENT_APPROVED),
                                    draft(LifecycleEventType.RESERVATION_EXPIRED),
                                    draft(LifecycleEventType.PAYMENT_REFUND_PENDING)
                            )
                    );
                }
            });
            durations.add(System.nanoTime() - started);
        }
        return durations;
    }

    private Reservation newReservation() {
        return Reservation.builder()
                .reservationCode("phase2-measurement-" + UUID.randomUUID())
                .totalPrice(10_000)
                .reservationStatus(ReservationStatus.PENDING_PAYMENT)
                .build();
    }

    private LifecycleEventDraft draft(LifecycleEventType type) {
        return new LifecycleEventDraft(
                type,
                LifecycleActorType.PAYMENT_VERIFICATION,
                null,
                null,
                new LifecycleEventPayload(List.of(), List.of(), "PHASE2_MEASUREMENT")
        );
    }

    private void print(String name, List<Long> durations) {
        Collections.sort(durations);
        double meanMs = durations.stream()
                .mapToDouble(value -> value / 1_000_000.0)
                .average()
                .orElse(0.0);
        System.out.printf(
                "%s.p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f mean_ms=%.3f throughput_tx_s=%.2f%n",
                name,
                percentileMs(durations, 0.50),
                percentileMs(durations, 0.95),
                percentileMs(durations, 0.99),
                meanMs,
                meanMs == 0.0 ? 0.0 : 1_000.0 / meanMs
        );
    }

    private double percentileMs(List<Long> sortedNanos, double percentile) {
        int index = Math.min(
                sortedNanos.size() - 1,
                (int) Math.ceil(percentile * sortedNanos.size()) - 1
        );
        return sortedNanos.get(index) / 1_000_000.0;
    }
}

package org.example.ticket.reservation.service;

import jakarta.persistence.EntityManager;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.payment.constant.PaymentAttemptStatus;
import org.example.ticket.payment.constant.PaymentOrderStatus;
import org.example.ticket.payment.dto.VerifiedPaymentSnapshot;
import org.example.ticket.payment.model.PaymentAttempt;
import org.example.ticket.payment.model.PaymentOrder;
import org.example.ticket.payment.repository.PaymentAttemptRepository;
import org.example.ticket.payment.repository.PaymentOrderRepository;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.performance.repository.PerformanceRepository;
import org.example.ticket.performance.repository.PerformanceTimeRepository;
import org.example.ticket.reservation.dto.ReservationExpirationResult;
import org.example.ticket.reservation.model.Reservation;
import org.example.ticket.reservation.model.ReservedSeat;
import org.example.ticket.reservation.model.Seat;
import org.example.ticket.reservation.repository.ReservationRepository;
import org.example.ticket.reservation.repository.SeatRepository;
import org.example.ticket.util.constant.ReservationStatus;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReservationCompletionService.class, ReservationExpirationService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MySqlReservationStateRaceTest {

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
            .withDatabaseName("imticket_race")
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
    private MemberRepository memberRepository;

    @Autowired
    private PerformanceRepository performanceRepository;

    @Autowired
    private PerformanceTimeRepository performanceTimeRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ReservationCompletionService reservationCompletionService;

    @Autowired
    private ReservationExpirationService reservationExpirationService;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @AfterEach
    void clearFixture() {
        requiresNewTransaction().executeWithoutResult(status -> {
            paymentAttemptRepository.deleteAllInBatch();
            paymentOrderRepository.deleteAllInBatch();
            entityManager.createQuery("delete from ReservedSeat").executeUpdate();
            entityManager.clear();
            reservationRepository.deleteAllInBatch();
            seatRepository.deleteAllInBatch();
            performanceTimeRepository.deleteAllInBatch();
            performanceRepository.deleteAllInBatch();
            memberRepository.deleteAllInBatch();
        });
    }

    @Test
    void completionWinnerRemainsSuccessfulWhenCleanupWaitsOnReservationLock() throws Exception {
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);
        Fixture fixture = createFixture(expiresAt, 2);
        LocalDateTime cleanupNow = expiresAt.plusMinutes(1);
        CountDownLatch completionChangedState = new CountDownLatch(1);
        CountDownLatch allowCompletionCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> completion = executor.submit(() -> requiresNewTransaction().executeWithoutResult(status -> {
                reservationCompletionService.complete(
                        fixture.paymentOrderId(),
                        fixture.walletAddress(),
                        fixture.snapshot()
                );
                completionChangedState.countDown();
                await(allowCompletionCommit);
            }));

            assertThat(completionChangedState.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ReservationExpirationResult> cleanup = executor.submit(
                    () -> reservationExpirationService.expireReservations(cleanupNow, 5000)
            );

            assertMySqlLockWaitObserved();
            assertBlocked(cleanup);
            allowCompletionCommit.countDown();

            completion.get(10, TimeUnit.SECONDS);
            assertThat(cleanup.get(10, TimeUnit.SECONDS))
                    .isEqualTo(ReservationExpirationResult.empty());

            FinalState finalState = finalState(fixture);
            assertThat(finalState.reservationStatus()).isEqualTo(ReservationStatus.SUCCESS);
            assertThat(finalState.seatStatuses()).hasSize(2).containsOnly(SeatStatus.RESERVED);
            assertThat(finalState.paymentOrderStatus()).isEqualTo(PaymentOrderStatus.APPLIED);
            assertThat(finalState.paymentAttemptStatus()).isEqualTo(PaymentAttemptStatus.PAID);
            assertThat(finalState.reservationExists()).isTrue();
        } finally {
            allowCompletionCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cleanupWinnerPreservesLateApprovalAsRefundPending() throws Exception {
        LocalDateTime expiresAt = LocalDateTime.now().minusMinutes(1);
        Fixture fixture = createFixture(expiresAt, 2);
        CountDownLatch cleanupChangedState = new CountDownLatch(1);
        CountDownLatch allowCleanupCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> cleanup = executor.submit(() -> requiresNewTransaction().executeWithoutResult(status -> {
                ReservationExpirationResult result =
                        reservationExpirationService.expireReservations(LocalDateTime.now(), 5000);
                assertThat(result).isEqualTo(new ReservationExpirationResult(1, 2));
                cleanupChangedState.countDown();
                await(allowCleanupCommit);
            }));

            assertThat(cleanupChangedState.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> completion = executor.submit(() -> reservationCompletionService.complete(
                    fixture.paymentOrderId(),
                    fixture.walletAddress(),
                    fixture.snapshot()
            ));

            assertMySqlLockWaitObserved();
            assertBlocked(completion);
            allowCleanupCommit.countDown();

            cleanup.get(10, TimeUnit.SECONDS);
            completion.get(10, TimeUnit.SECONDS);

            FinalState finalState = finalState(fixture);
            assertThat(finalState.reservationStatus()).isEqualTo(ReservationStatus.EXPIRED);
            assertThat(finalState.seatStatuses()).hasSize(2).containsOnly(SeatStatus.AVAILABLE);
            assertThat(finalState.paymentOrderStatus()).isEqualTo(PaymentOrderStatus.REFUND_PENDING);
            assertThat(finalState.paymentAttemptStatus()).isEqualTo(PaymentAttemptStatus.PAID);
            assertThat(finalState.providerTransactionId()).isEqualTo(fixture.snapshot().providerTransactionId());
            assertThat(finalState.reservationExists()).isTrue();
        } finally {
            allowCleanupCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void versionedMigrationUpgradesLegacyReservationStatusEnumBeforeExpirationWrite() throws Exception {
        LocalDateTime expiresAt = LocalDateTime.now().minusMinutes(1);
        Fixture fixture = createFixture(expiresAt, 1);

        jdbcTemplate.execute("""
                alter table `Reservation`
                modify column `reservation_status`
                enum('LOCKED', 'PENDING_PAYMENT', 'SUCCESS') not null
                """);
        assertThat(reservationStatusColumnType()).doesNotContain("EXPIRED");

        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new FileSystemResource(
                            "scripts/db/migrations/V20260718_01__add_reservation_expired_status.sql"
                    )
            );
        }

        assertThat(reservationStatusColumnType()).contains("EXPIRED");
        assertThat(reservationExpirationService.expireReservations(LocalDateTime.now(), 5000))
                .isEqualTo(new ReservationExpirationResult(1, 1));
        assertThat(finalState(fixture).reservationStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    private Fixture createFixture(LocalDateTime expiresAt, int seatCount) {
        return requiresNewTransaction().execute(status -> {
            String suffix = UUID.randomUUID().toString();
            String walletAddress = "0x" + suffix.replace("-", "");
            Member member = memberRepository.save(Member.builder()
                    .walletAddress(walletAddress)
                    .nickname("member-" + suffix)
                    .role("ROLE_USER")
                    .build());
            Performance performance = performanceRepository.save(Performance.builder()
                    .title("race-" + suffix)
                    .build());
            PerformanceTime performanceTime = performanceTimeRepository.save(PerformanceTime.builder()
                    .performance(performance)
                    .showDate(LocalDate.now().plusDays(1))
                    .showTime(LocalTime.NOON)
                    .build());
            List<Seat> seats = seatRepository.saveAll(java.util.stream.IntStream.range(0, seatCount)
                    .mapToObj(index -> Seat.builder()
                            .seatFloor(1)
                            .seatSection("A")
                            .seatRow(1)
                            .seatNumber(index + 1)
                            .seatType(SeatInfo.VIP)
                            .price(45000)
                            .seatStatus(SeatStatus.LOCKED)
                            .performanceTime(performanceTime)
                            .build())
                    .toList());
            Reservation reservation = Reservation.builder()
                    .reservationCode("reservation-" + suffix)
                    .member(member)
                    .totalPrice(45000 * seatCount)
                    .reservationStatus(ReservationStatus.PENDING_PAYMENT)
                    .expiredTime(expiresAt)
                    .build();
            reservation.setReservedSeats(seats.stream()
                    .map(seat -> ReservedSeat.builder()
                            .reservation(reservation)
                            .seat(seat)
                            .build())
                    .toList());
            reservationRepository.save(reservation);
            PaymentOrder order = paymentOrderRepository.save(PaymentOrder.builder()
                    .reservation(reservation)
                    .member(member)
                    .merchantOrderId("merchant-" + suffix)
                    .amount(45000 * seatCount)
                    .currency("KRW")
                    .status(PaymentOrderStatus.READY)
                    .idempotencyKey("payment-" + suffix)
                    .requestHash(suffix.replace("-", ""))
                    .build());
            paymentAttemptRepository.save(PaymentAttempt.builder()
                    .paymentOrder(order)
                    .attemptId("attempt-" + suffix)
                    .provider("FAKE")
                    .status(PaymentAttemptStatus.READY)
                    .build());

            VerifiedPaymentSnapshot snapshot = new VerifiedPaymentSnapshot(
                    order.getMerchantOrderId(),
                    "provider-" + suffix,
                    order.getAmount(),
                    order.getCurrency(),
                    LocalDateTime.now()
            );
            return new Fixture(reservation.getId(), order.getId(), walletAddress, snapshot);
        });
    }

    private FinalState finalState(Fixture fixture) {
        return requiresNewTransaction().execute(status -> {
            Reservation reservation = reservationRepository.findById(fixture.reservationId()).orElseThrow();
            List<Long> seatIds = seatRepository.findIdsByReservationIds(List.of(fixture.reservationId()));
            List<Seat> seats = seatRepository.findAllById(seatIds);
            PaymentOrder order = paymentOrderRepository.findById(fixture.paymentOrderId()).orElseThrow();
            PaymentAttempt attempt = paymentAttemptRepository
                    .findTopByPaymentOrderIdOrderByCreatedAtDesc(fixture.paymentOrderId())
                    .orElseThrow();
            return new FinalState(
                    reservation.getReservationStatus(),
                    seats.stream().map(Seat::getSeatStatus).toList(),
                    order.getStatus(),
                    attempt.getStatus(),
                    attempt.getProviderTransactionId(),
                    true
            );
        });
    }

    private TransactionTemplate requiresNewTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private String reservationStatusColumnType() {
        return jdbcTemplate.queryForObject(
                "show columns from `Reservation` where Field = 'reservation_status'",
                (resultSet, rowNumber) -> resultSet.getString("Type")
        );
    }

    private void assertBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    private void assertMySqlLockWaitObserved() {
        boolean observed = false;
        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                "root",
                MYSQL.getPassword()
        ); var statement = connection.createStatement()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                try (var resultSet = statement.executeQuery(
                        "select count(*) from performance_schema.data_lock_waits"
                )) {
                    resultSet.next();
                    if (resultSet.getInt(1) > 0) {
                        observed = true;
                        break;
                    }
                }
                Thread.sleep(20);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MySQL lock wait 관측이 중단되었습니다.", exception);
        } catch (SQLException exception) {
            throw new IllegalStateException("MySQL lock wait를 조회하지 못했습니다.", exception);
        }
        assertThat(observed).as("performance_schema에서 row lock wait가 관측되어야 합니다.").isTrue();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 latch가 제한 시간 안에 열리지 않았습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트가 중단되었습니다.", exception);
        }
    }

    private record Fixture(
            Long reservationId,
            Long paymentOrderId,
            String walletAddress,
            VerifiedPaymentSnapshot snapshot
    ) {
    }

    private record FinalState(
            ReservationStatus reservationStatus,
            List<SeatStatus> seatStatuses,
            PaymentOrderStatus paymentOrderStatus,
            PaymentAttemptStatus paymentAttemptStatus,
            String providerTransactionId,
            boolean reservationExists
    ) {
    }
}

package org.example.ticket.reservation.booking.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.performance.repository.PerformanceRepository;
import org.example.ticket.performance.repository.PerformanceTimeRepository;
import org.example.ticket.reservation.booking.domain.ReservationErrorCode;
import org.example.ticket.reservation.booking.support.ReservationSnapshotException;
import org.example.ticket.reservation.booking.concurrency.SeatAdmissionService;
import org.example.ticket.reservation.booking.concurrency.ReservationLockAspect;
import org.example.ticket.reservation.booking.concurrency.ReservationLockStrategyContext;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.domain.ReservationIdempotencyStatus;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.reservation.booking.persistence.ReservationIdempotencyRepository;
import org.example.ticket.reservation.booking.persistence.ReservationRepository;
import org.example.ticket.reservation.booking.persistence.SeatRepository;
import org.example.ticket.reservation.booking.api.ReservationRequest;
import org.example.ticket.reservation.booking.support.ReservationRequestHasher;
import org.example.ticket.reservation.booking.support.ReservationResponseSnapshotCodec;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.example.ticket.util.constant.ReservationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.example.ticket.config.AsyncConfig;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "reservation.lock-strategy=single-thread"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableAspectJAutoProxy
@Import({
        ReservationIdempotencyTransactionService.class,
        ReservationIdempotentCreationService.class,
        ReservationPreReserveService.class,
        ReservationRequestHasher.class,
        ReservationResponseSnapshotCodec.class,
        SeatAdmissionService.class,
        ReservationService.class,
        ReservationExpirationService.class,
        ReservationLockAspect.class,
        ReservationLockStrategyContext.class,
        AsyncConfig.class,
        MySqlReservationIdempotencyTest.JsonTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MySqlReservationIdempotencyTest {

    private static final String KEY = "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1";
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
            .withDatabaseName("imticket_idempotency")
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
    private ReservationIdempotencyRepository idempotencyRepository;

    @Autowired
    private ReservationIdempotencyTransactionService transactionService;

    @Autowired
    private ReservationIdempotentCreationService creationService;

    @Autowired
    private ReservationPreReserveService preReserveService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @MockitoBean
    private SeatService seatService;

    @MockitoSpyBean
    private ReservationResponseSnapshotCodec snapshotCodec;

    @MockitoSpyBean
    private SeatAdmissionService seatAdmissionService;

    @AfterEach
    void clearFixture() {
        requiresNewTransaction().executeWithoutResult(status -> {
            idempotencyRepository.deleteAllInBatch();
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
    void versionedDdlCreatesClaimBoundaryUsedByJpa() throws Exception {
        Member member = createMember("ddl");
        jdbcTemplate.execute("drop table `reservation_idempotency`");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new FileSystemResource(
                            "scripts/db/migrations/V20260718_02__create_reservation_idempotency.sql"
                    )
            );
        }

        var claim = transactionService.createClaim(
                member.getId(), KEY, HASH, UUID.randomUUID().toString(), LocalDateTime.now().plusSeconds(30)
        );
        requiresNewTransaction().executeWithoutResult(status -> {
            Reservation reservation = reservationRepository.save(Reservation.builder()
                    .reservationCode("ddl-reservation-" + UUID.randomUUID())
                    .totalPrice(10000)
                    .member(memberRepository.findById(member.getId()).orElseThrow())
                    .reservationStatus(ReservationStatus.PENDING_PAYMENT)
                    .expiredTime(LocalDateTime.now().plusMinutes(7))
                    .build());
            idempotencyRepository.findByIdForUpdate(claim.id()).orElseThrow()
                    .markSucceeded(reservation, 1, "{}");
        });
        String createTable = jdbcTemplate.queryForObject(
                "show create table `reservation_idempotency`",
                (resultSet, rowNumber) -> resultSet.getString(2)
        );

        assertThat(idempotencyRepository.findById(claim.id()).orElseThrow().getStatus())
                .isEqualTo(ReservationIdempotencyStatus.SUCCEEDED);
        assertThat(createTable)
                .contains("uk_reservation_idempotency_member_key")
                .contains("chk_reservation_idempotency_success_snapshot")
                .contains("ascii_bin");
        entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getSchemaManager()
                .validateMappedObjects();
    }

    @Test
    void concurrentSameMemberAndKeyHasExactlyOneClaimWinner() throws Exception {
        Member member = createMember("same-key");
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    try {
                        transactionService.createClaim(
                                member.getId(),
                                KEY,
                                HASH,
                                UUID.randomUUID().toString(),
                                LocalDateTime.now().plusSeconds(30)
                        );
                        return true;
                    } catch (org.springframework.dao.DataIntegrityViolationException expected) {
                        return false;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
            assertThat(idempotencyRepository.count()).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void sameKeyForDifferentMembersIsAllowed() {
        Member first = createMember("member-a");
        Member second = createMember("member-b");

        transactionService.createClaim(
                first.getId(), KEY, HASH, UUID.randomUUID().toString(), LocalDateTime.now().plusSeconds(30)
        );
        transactionService.createClaim(
                second.getId(), KEY, HASH, UUID.randomUUID().toString(), LocalDateTime.now().plusSeconds(30)
        );

        assertThat(idempotencyRepository.count()).isEqualTo(2);
    }

    @Test
    void onlyOneFailedClaimReclaimSucceeds() throws Exception {
        Member member = createMember("reclaim");
        String firstToken = UUID.randomUUID().toString();
        var claim = transactionService.createClaim(
                member.getId(), KEY, HASH, firstToken, LocalDateTime.now().plusSeconds(30)
        );
        assertThat(transactionService.markFailedIfOwned(
                claim.id(), firstToken, "SEAT_ADMISSION_REJECTED", LocalDateTime.now()
        )).isTrue();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = List.of(
                    executor.submit(() -> reclaimAfter(start, claim.id())),
                    executor.submit(() -> reclaimAfter(start, claim.id()))
            );
            start.countDown();

            assertThat(List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder(true, false);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void staleProcessingReclaimFencesOldAttemptBeforeReservationCreation() {
        Member member = createMember("fence");
        String oldToken = UUID.randomUUID().toString();
        var claim = transactionService.createClaim(
                member.getId(), KEY, HASH, oldToken, LocalDateTime.now().minusSeconds(1)
        );
        String newToken = UUID.randomUUID().toString();
        assertThat(transactionService.tryReclaim(
                claim.id(), HASH, newToken, LocalDateTime.now(), LocalDateTime.now().plusSeconds(30)
        )).isTrue();

        assertThatThrownBy(() -> creationService.create(
                member.getId(),
                member.getWalletAddress(),
                new ReservationRequest(1L, List.of(1L)),
                HASH,
                claim.id(),
                oldToken
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ReservationErrorCode.IDEMPOTENCY_PROCESSING));
        verify(seatService, never()).findAndLockSeatsByPerformanceTime(any(), any());
    }

    @Test
    void processingDuplicateSkipsAdmissionThenReplaysAfterWinnerCommit() throws Exception {
        CreationBase base = createCreationBase("facade-race");
        ReservationRequest request = new ReservationRequest(base.performanceTimeId(), List.of(base.seatId()));
        CreationFixture fixture = new CreationFixture(
                base.memberId(), base.walletAddress(), base.seatId(), null, null, request
        );
        stubSeatCreation(fixture);
        CountDownLatch snapshotEncoding = new CountDownLatch(1);
        CountDownLatch allowWinnerCommit = new CountDownLatch(1);
        doAnswer(invocation -> {
            snapshotEncoding.countDown();
            await(allowWinnerCommit);
            return invocation.callRealMethod();
        }).when(snapshotCodec).encode(any());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<org.example.ticket.reservation.booking.api.ReservationCreateResponse> winner =
                    executor.submit(() -> preReserveService.preReserve(
                            base.walletAddress(), KEY, request
                    ));
            assertThat(snapshotEncoding.await(5, TimeUnit.SECONDS)).isTrue();
            Future<org.example.ticket.reservation.booking.api.ReservationCreateResponse> processingDuplicate =
                    executor.submit(() -> preReserveService.preReserve(
                            base.walletAddress(), KEY, request
                    ));

            assertThatThrownBy(() -> processingDuplicate.get(5, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, exception ->
                            assertThat(exception.getCause())
                                    .isInstanceOfSatisfying(BusinessException.class, businessException ->
                                            assertThat(businessException.getErrorCode())
                                                    .isEqualTo(ReservationErrorCode.IDEMPOTENCY_PROCESSING)));
            verify(seatAdmissionService, times(1)).execute(eq(request), any());
            allowWinnerCommit.countDown();

            var winnerResponse = winner.get(10, TimeUnit.SECONDS);
            var replayResponse = preReserveService.preReserve(base.walletAddress(), KEY, request);
            assertThat(replayResponse.getId()).isEqualTo(winnerResponse.getId());
            assertThat(replayResponse.getOrderUid()).isEqualTo(winnerResponse.getOrderUid());
            assertThat(replayResponse.getExpiredTime()).isEqualTo(winnerResponse.getExpiredTime());
            assertThat(reservationRepository.count()).isEqualTo(1);
            assertThat(idempotencyRepository.count()).isEqualTo(1);
            assertThat(idempotencyRepository.findAll().getFirst().getStatus())
                    .isEqualTo(ReservationIdempotencyStatus.SUCCEEDED);
            verify(seatAdmissionService, times(1)).execute(eq(request), any());
        } finally {
            allowWinnerCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void reservationAndSucceededSnapshotCommitTogether() {
        CreationFixture fixture = createCreationFixture("atomic-success");
        stubSeatCreation(fixture);
        AtomicReference<String> executionThread = new AtomicReference<>();
        doAnswer(invocation -> {
            executionThread.set(Thread.currentThread().getName());
            return "snapshot-v1";
        }).when(snapshotCodec).encode(any());

        var response = creationService.create(
                fixture.memberId(),
                fixture.walletAddress(),
                fixture.request(),
                HASH,
                fixture.claimId(),
                fixture.attemptToken()
        );

        assertThat(response.getId()).isNotNull();
        assertThat(reservationRepository.count()).isEqualTo(1);
        var claim = idempotencyRepository.findById(fixture.claimId()).orElseThrow();
        assertThat(claim.getStatus()).isEqualTo(ReservationIdempotencyStatus.SUCCEEDED);
        assertThat(claim.getReservation().getId()).isEqualTo(response.getId());
        assertThat(claim.getResponsePayload()).isEqualTo("snapshot-v1");
        assertThat(executionThread.get()).startsWith("ReservationSingle-");
    }

    @Test
    void snapshotFailureRollsBackReservationAndLeavesClaimRetryableByLease() {
        CreationFixture fixture = createCreationFixture("atomic-rollback");
        stubSeatCreation(fixture);
        doThrow(new ReservationSnapshotException("snapshot failure"))
                .when(snapshotCodec).encode(any());

        assertThatThrownBy(() -> creationService.create(
                fixture.memberId(),
                fixture.walletAddress(),
                fixture.request(),
                HASH,
                fixture.claimId(),
                fixture.attemptToken()
        )).isInstanceOf(ReservationSnapshotException.class);

        assertThat(reservationRepository.count()).isZero();
        assertThat(idempotencyRepository.findById(fixture.claimId()).orElseThrow().getStatus())
                .isEqualTo(ReservationIdempotencyStatus.PROCESSING);
        assertThat(seatRepository.findById(fixture.seatId()).orElseThrow().getSeatStatus())
                .isEqualTo(SeatStatus.AVAILABLE);
    }

    private void stubSeatCreation(CreationFixture fixture) {
        when(seatService.findAndLockSeatsByPerformanceTime(
                fixture.request().getPerformanceTimeId(),
                fixture.request().getSeatIds()
        )).thenAnswer(invocation -> seatRepository.findAllById(fixture.request().getSeatIds()));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Seat> seats = invocation.getArgument(0);
            SeatStatus status = invocation.getArgument(1);
            seats.forEach(seat -> seat.markAsReserved(status));
            return null;
        }).when(seatService).changeSeatsState(any(), eq(SeatStatus.LOCKED));
    }

    private CreationFixture createCreationFixture(String suffix) {
        CreationBase base = createCreationBase(suffix);
        String token = UUID.randomUUID().toString();
        var claim = transactionService.createClaim(
                base.memberId(),
                UUID.randomUUID().toString(),
                HASH,
                token,
                LocalDateTime.now().plusSeconds(30)
        );
        return new CreationFixture(
                base.memberId(),
                base.walletAddress(),
                base.seatId(),
                claim.id(),
                token,
                new ReservationRequest(base.performanceTimeId(), List.of(base.seatId()))
        );
    }

    private CreationBase createCreationBase(String suffix) {
        return requiresNewTransaction().execute(status -> {
            Member member = memberRepository.save(member(suffix));
            Performance performance = performanceRepository.save(Performance.builder()
                    .title("performance-" + suffix)
                    .build());
            PerformanceTime performanceTime = performanceTimeRepository.save(PerformanceTime.builder()
                    .performance(performance)
                    .showDate(LocalDate.now().plusDays(1))
                    .showTime(LocalTime.NOON)
                    .build());
            Seat seat = seatRepository.save(Seat.builder()
                    .seatFloor(1)
                    .seatSection("A")
                    .seatRow(1)
                    .seatNumber(1)
                    .seatType(SeatInfo.VIP)
                    .price(45000)
                    .seatStatus(SeatStatus.AVAILABLE)
                    .performanceTime(performanceTime)
                    .build());
            return new CreationBase(
                    member.getId(),
                    member.getWalletAddress(),
                    seat.getId(),
                    performanceTime.getId()
            );
        });
    }

    private boolean reclaimAfter(CountDownLatch start, Long claimId) {
        await(start);
        LocalDateTime now = LocalDateTime.now();
        return transactionService.tryReclaim(
                claimId,
                HASH,
                UUID.randomUUID().toString(),
                now,
                now.plusSeconds(30)
        );
    }

    private Member createMember(String suffix) {
        return requiresNewTransaction().execute(status -> memberRepository.save(member(suffix)));
    }

    private Member member(String suffix) {
        return Member.builder()
                .walletAddress("0x" + suffix + UUID.randomUUID().toString().replace("-", ""))
                .nickname("member-" + suffix + UUID.randomUUID())
                .role("ROLE_USER")
                .build();
    }

    private TransactionTemplate requiresNewTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 latch가 열리지 않았습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트가 중단되었습니다.", exception);
        }
    }

    private record CreationFixture(
            Long memberId,
            String walletAddress,
            Long seatId,
            Long claimId,
            String attemptToken,
            ReservationRequest request
    ) {
    }

    private record CreationBase(
            Long memberId,
            String walletAddress,
            Long seatId,
            Long performanceTimeId
    ) {
    }

    @TestConfiguration
    static class JsonTestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}

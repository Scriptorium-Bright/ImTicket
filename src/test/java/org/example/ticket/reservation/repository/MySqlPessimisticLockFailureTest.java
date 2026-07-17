package org.example.ticket.reservation.repository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfEnvironmentVariable(named = "MYSQL_LOCK_TEST_ENABLED", matches = "(?i)true")
class MySqlPessimisticLockFailureTest {

    private static final int MYSQL_LOCK_WAIT_TIMEOUT_ERROR = 1205;
    private static final int MYSQL_DEADLOCK_ERROR = 1213;
    private static final String TABLE_NAME = "imticket_lock_test_seat_" + ProcessHandle.current().pid();

    @BeforeAll
    static void createFixtureTable() throws SQLException {
        try (Connection connection = newConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS " + table());
            statement.executeUpdate("""
                    CREATE TABLE %s (
                        id BIGINT NOT NULL PRIMARY KEY,
                        seat_status VARCHAR(20) NOT NULL
                    ) ENGINE=InnoDB
                    """.formatted(table()));
        }
    }

    @BeforeEach
    void resetFixtureRows() throws SQLException {
        try (Connection connection = newConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + table());
            statement.executeUpdate("INSERT INTO " + table()
                    + " (id, seat_status) VALUES (1, 'AVAILABLE'), (2, 'AVAILABLE')");
        }
    }

    @AfterAll
    static void dropFixtureTable() throws SQLException {
        try (Connection connection = newConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS " + table());
        }
    }

    @Test
    @DisplayName("SELECT FOR UPDATE는 선행 트랜잭션이 끝날 때까지 lock wait 상태가 된다")
    void waitsForOwningTransactionToFinish() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection owner = transactionalConnection();
             Connection waiter = transactionalConnection()) {
            lockSeat(owner, 1L);

            CountDownLatch attemptStarted = new CountDownLatch(1);
            Future<Duration> waitingLock = executor.submit(() -> {
                attemptStarted.countDown();
                long startedAt = System.nanoTime();
                lockSeat(waiter, 1L);
                return Duration.ofNanos(System.nanoTime() - startedAt);
            });

            assertThat(attemptStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> waitingLock.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            owner.commit();

            Duration waited = waitingLock.get(3, TimeUnit.SECONDS);
            waiter.rollback();

            assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(250));
            System.out.printf(
                    "[LOCK-EVIDENCE] scenario=row-lock-wait waited_ms=%d%n",
                    waited.toMillis()
            );
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("lock 보유 시간이 innodb_lock_wait_timeout을 넘으면 1205가 발생한다")
    void timesOutWhenLockIsHeldPastSessionLimit() throws Exception {
        try (Connection owner = transactionalConnection();
             Connection waiter = transactionalConnection()) {
            setLockWaitTimeout(waiter, 1);
            lockSeat(owner, 1L);

            long startedAt = System.nanoTime();
            SQLException exception = assertThrows(SQLException.class, () -> lockSeat(waiter, 1L));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(exception.getErrorCode()).isEqualTo(MYSQL_LOCK_WAIT_TIMEOUT_ERROR);
            assertThat(exception.getSQLState()).isEqualTo("40001"); // MySQL 8.0/9.x returns 40001 for Lock Wait Timeout
            assertThat(elapsed).isBetween(Duration.ofMillis(800), Duration.ofSeconds(4));
            System.out.printf(
                    "[LOCK-EVIDENCE] scenario=lock-wait-timeout error_code=%d sql_state=%s elapsed_ms=%d%n",
                    exception.getErrorCode(),
                    exception.getSQLState(),
                    elapsed.toMillis()
            );

            waiter.rollback();
            owner.rollback();
        }
    }

    @Test
    @DisplayName("두 트랜잭션이 좌석을 반대 순서로 잠그면 한 트랜잭션이 1213 deadlock victim이 된다")
    void detectsDeadlockWhenSeatsAreLockedInOppositeOrder() throws Exception {
        assumeTrue(isDeadlockDetectionEnabled(), "MySQL innodb_deadlock_detect가 활성화되어야 한다.");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier firstLocksAcquired = new CyclicBarrier(2);

        try {
            Future<LockOutcome> first = executor.submit(
                    () -> lockInOrder(1L, 2L, firstLocksAcquired));
            Future<LockOutcome> second = executor.submit(
                    () -> lockInOrder(2L, 1L, firstLocksAcquired));

            List<LockOutcome> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );

            assertThat(outcomes).filteredOn(LockOutcome::success).hasSize(1);
            assertThat(outcomes)
                    .filteredOn(outcome -> outcome.errorCode() == MYSQL_DEADLOCK_ERROR)
                    .singleElement()
                    .satisfies(outcome -> assertThat(outcome.sqlState()).isEqualTo("40001"));
            System.out.printf(
                    "[LOCK-EVIDENCE] scenario=reverse-order-deadlock outcomes=%s%n",
                    outcomes
            );
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static LockOutcome lockInOrder(
            long firstSeatId,
            long secondSeatId,
            CyclicBarrier firstLocksAcquired
    ) throws SQLException, InterruptedException, BrokenBarrierException, TimeoutException {
        try (Connection connection = transactionalConnection()) {
            setLockWaitTimeout(connection, 5);
            lockSeat(connection, firstSeatId);
            firstLocksAcquired.await(3, TimeUnit.SECONDS);

            try {
                lockSeat(connection, secondSeatId);
                connection.commit();
                return LockOutcome.succeeded();
            } catch (SQLException exception) {
                connection.rollback();
                return LockOutcome.failed(exception);
            }
        }
    }

    private static void lockSeat(Connection connection, long seatId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT id FROM " + table() + " WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, seatId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getLong("id")).isEqualTo(seatId);
            }
        }
    }

    private static boolean isDeadlockDetectionEnabled() throws SQLException {
        try (Connection connection = newConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT @@innodb_deadlock_detect")) {
            return resultSet.next() && resultSet.getInt(1) == 1;
        }
    }

    private static void setLockWaitTimeout(Connection connection, int seconds) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION innodb_lock_wait_timeout = " + seconds);
        }
    }

    private static Connection transactionalConnection() throws SQLException {
        Connection connection = newConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                environment("MYSQL_LOCK_TEST_URL",
                        "jdbc:mysql://127.0.0.1:10047/capstone"
                                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul"),
                environment("MYSQL_LOCK_TEST_USERNAME", "capstone"),
                requiredPassword()
        );
    }

    private static String requiredPassword() {
        String password = System.getenv("MYSQL_LOCK_TEST_PASSWORD");
        if (password == null || password.isBlank()) {
            password = System.getenv("MYSQL_PASSWORD");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "MYSQL_LOCK_TEST_PASSWORD 또는 MYSQL_PASSWORD를 설정해야 한다."
            );
        }
        return password;
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String table() {
        return "`" + TABLE_NAME + "`";
    }

    private record LockOutcome(boolean success, int errorCode, String sqlState) {

        static LockOutcome succeeded() {
            return new LockOutcome(true, 0, null);
        }

        static LockOutcome failed(SQLException exception) {
            return new LockOutcome(false, exception.getErrorCode(), exception.getSQLState());
        }
    }
}

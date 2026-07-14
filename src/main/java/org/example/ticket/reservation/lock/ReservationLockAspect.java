package org.example.ticket.reservation.lock;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.example.ticket.reservation.request.ReservationRequest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReservationLockAspect {

    private final DataSource dataSource;
    private final AsyncTaskExecutor reservationSingleThreadExecutor;

    public ReservationLockAspect(
            DataSource dataSource,
            @Qualifier("reservationSingleThreadTaskExecutor") AsyncTaskExecutor reservationSingleThreadExecutor
    ) {
        this.dataSource = dataSource;
        this.reservationSingleThreadExecutor = reservationSingleThreadExecutor;
    }

    @Value("${reservation.lock-strategy:pessimistic}")
    private String configuredStrategy;

    @Value("${reservation.lock.named-timeout-seconds:5}")
    private int namedLockTimeoutSeconds;

    private final ConcurrentMap<Long, Object> monitors = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, ReentrantLock> reentrantLocks = new ConcurrentHashMap<>();

    @Around("execution(* org.example.ticket.reservation.service.ReservationService.createReservation(..))")
    public Object lockReservationSeats(ProceedingJoinPoint joinPoint) throws Throwable {
        ReservationRequest request = findRequest(joinPoint.getArgs());
        List<Long> seatIds = normalizeSeatIds(request);
        if (seatIds.isEmpty()) {
            return joinPoint.proceed();
        }

        return switch (strategy()) {
            case SYNCHRONIZED -> withSynchronizedLocks(seatIds, joinPoint::proceed, 0);
            case REENTRANT -> withReentrantLocks(seatIds, joinPoint::proceed, 0);
            case MYSQL_NAMED -> withMysqlNamedLocks(seatIds, joinPoint::proceed);
            case SINGLE_THREAD -> withSingleThread(joinPoint::proceed);
            case PESSIMISTIC, OPTIMISTIC -> joinPoint.proceed();
        };
    }

    private Object withSingleThread(ThrowingOperation operation) throws Throwable {
        Future<Object> future = reservationSingleThreadExecutor.submit(() -> {
            try {
                return operation.run();
            } catch (Throwable throwable) {
                throw new CompletionException(throwable);
            }
        });

        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            throw cause;
        }
    }

    private Object withSynchronizedLocks(
            List<Long> seatIds,
            ThrowingOperation operation,
            int index
    ) throws Throwable {
        if (index == seatIds.size()) {
            return operation.run();
        }

        Object monitor = monitors.computeIfAbsent(seatIds.get(index), ignored -> new Object());
        synchronized (monitor) {
            return withSynchronizedLocks(seatIds, operation, index + 1);
        }
    }

    private Object withReentrantLocks(
            List<Long> seatIds,
            ThrowingOperation operation,
            int index
    ) throws Throwable {
        if (index == seatIds.size()) {
            return operation.run();
        }

        ReentrantLock lock = reentrantLocks.computeIfAbsent(
                seatIds.get(index),
                ignored -> new ReentrantLock(true)
        );
        lock.lock();
        try {
            return withReentrantLocks(seatIds, operation, index + 1);
        } finally {
            lock.unlock();
        }
    }

    private Object withMysqlNamedLocks(List<Long> seatIds, ThrowingOperation operation) throws Throwable {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        List<String> acquiredNames = new ArrayList<>();
        try {
            for (Long seatId : seatIds) {
                String lockName = "imticket:reservation:seat:" + seatId;
                if (!acquireNamedLock(connection, lockName)) {
                    throw new CannotAcquireLockException("MySQL named lock 획득에 실패했습니다: " + lockName);
                }
                acquiredNames.add(lockName);
            }
            return operation.run();
        } finally {
            for (int index = acquiredNames.size() - 1; index >= 0; index--) {
                releaseNamedLock(connection, acquiredNames.get(index));
            }
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private boolean acquireNamedLock(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)");) {
            statement.setString(1, lockName);
            statement.setInt(2, namedLockTimeoutSeconds);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private void releaseNamedLock(Connection connection, String lockName) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, lockName);
            statement.executeQuery();
        } catch (SQLException ignored) {
            // 원래 예외가 있는 경우 release 실패가 원인을 덮어쓰지 않도록 한다.
        }
    }

    private ReservationLockStrategy strategy() {
        return ReservationLockStrategy.from(configuredStrategy);
    }

    private ReservationRequest findRequest(Object[] arguments) {
        for (Object argument : arguments) {
            if (argument instanceof ReservationRequest request) {
                return request;
            }
        }
        return null;
    }

    private List<Long> normalizeSeatIds(ReservationRequest request) {
        if (request == null || request.getSeatIds() == null || request.getSeatIds().isEmpty()) {
            return List.of();
        }
        return request.getSeatIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        Object run() throws Throwable;
    }
}

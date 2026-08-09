package org.example.ticket.reservation.booking.util.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.AsyncTaskExecutor;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.booking.dto.request.ReservationRequest;
import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.util.annotation.ReservationLock;
import org.example.ticket.reservation.booking.util.lock.ReservationLockOperation;
import org.example.ticket.reservation.booking.util.lock.ReservationLockStrategy;
import org.example.ticket.reservation.booking.util.lock.ReservationLockStrategyContext;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReservationLockAspect {

    private final DataSource dataSource;
    private final AsyncTaskExecutor reservationSingleThreadExecutor;
    private final ReservationLockStrategyContext strategyContext;

    /**
     * lock 전략별 실행기와 MySQL named lock 의존성을 주입한다.
     * AOP 진입 시 설정된 전략을 동일한 실행 경계에서 처리한다.
     */
    public ReservationLockAspect(
            DataSource dataSource,
            @Qualifier("reservationSingleThreadTaskExecutor") AsyncTaskExecutor reservationSingleThreadExecutor,
            ReservationLockStrategyContext strategyContext
    ) {
        this.dataSource = dataSource;
        this.reservationSingleThreadExecutor = reservationSingleThreadExecutor;
        this.strategyContext = strategyContext;
    }

    @Value("${reservation.lock-strategy:reentrant}")
    private String configuredStrategy;

    @Value("${reservation.lock.named-timeout-seconds:5}")
    private int namedLockTimeoutSeconds;

    @Value("${reservation.lock.reentrant.wait-timeout-millis:1000}")
    private long reentrantLockWaitTimeoutMillis;

    private final ConcurrentMap<Long, Object> monitors = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, ReentrantLock> reentrantLocks = new ConcurrentHashMap<>();

    /**
     * ReservationLock이 붙은 예약 메서드를 가로채 lock 전략을 적용한다.
     * 대상 메서드의 어노테이션을 읽어 명시적 실행 경계로 전달한다.
     */
    @Around("@annotation(org.example.ticket.reservation.booking.util.annotation.ReservationLock)")
    public Object lockReservationSeats(ProceedingJoinPoint joinPoint) throws Throwable {
        ReservationLock reservationLock = ((MethodSignature) joinPoint.getSignature())
                .getMethod()
                .getAnnotation(ReservationLock.class);
        if (reservationLock == null) {
            throw new IllegalStateException("@ReservationLock 어노테이션을 읽지 못했습니다.");
        }
        return lockReservationSeats(joinPoint, reservationLock);
    }

    /**
     * 테스트와 명시적 호출에서 전략을 주입할 수 있도록 남겨 둔 순수 실행 경계다.
     * Spring AOP 진입점은 위 메서드에서 실제 메서드 어노테이션을 읽어 이 경계를 호출한다.
     */
    public Object lockReservationSeats(
            ProceedingJoinPoint joinPoint,
            ReservationLock reservationLock
    ) throws Throwable {
        ReservationRequest request = findRequest(joinPoint.getArgs());
        if (request == null) {
            throw new IllegalArgumentException(
                    "@ReservationLock 대상 메서드에는 ReservationRequest 인자가 필요합니다."
            );
        }
        List<Long> seatIds = normalizeSeatIds(request);
        if (seatIds.isEmpty()) {
            return joinPoint.proceed();
        }

        ReservationLockStrategy strategy = resolveStrategy(reservationLock.strategy());
        ReservationLockOperation<Object> operation = () -> strategyContext.withStrategy(strategy, joinPoint::proceed);

        return switch (strategy) {
            case SYNCHRONIZED -> withSynchronizedLocks(seatIds, operation, 0);
            case REENTRANT -> withReentrantLocks(seatIds, operation, 0);
            case MYSQL_NAMED -> withMysqlNamedLocks(seatIds, operation);
            case SINGLE_THREAD -> withSingleThread(operation);
            case PESSIMISTIC, OPTIMISTIC -> operation.run();
            case CONFIGURED -> throw new IllegalStateException("해석되지 않은 ReservationLock 전략입니다.");
        };
    }

    /**
     * 하나의 executor로 예약 작업을 직렬 실행한다.
     * 작업 예외와 interrupt 상태를 원래 호출자에게 전달한다.
     */
    private Object withSingleThread(ReservationLockOperation<Object> operation) throws Throwable {
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

    /**
     * 좌석 ID 순서대로 JVM monitor를 중첩 획득한다.
     * 다중 좌석 요청이 동일한 lock 순서를 사용하게 한다.
     */
    private Object withSynchronizedLocks(
            List<Long> seatIds,
            ReservationLockOperation<Object> operation,
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

    /**
     * 좌석별 공정 ReentrantLock을 timeout 안에 순서대로 획득한다.
     * 작업이 끝나면 재귀 호출이 풀리는 순서로 lock을 반납한다.
     */
    private Object withReentrantLocks(
            List<Long> seatIds,
            ReservationLockOperation<Object> operation,
            int index
    ) throws Throwable {
        if (index == seatIds.size()) {
            return operation.run();
        }

        ReentrantLock lock = reentrantLocks.computeIfAbsent(
                seatIds.get(index),
                ignored -> new ReentrantLock(true)
        );
        boolean acquired;
        try {
            acquired = lock.tryLock(reentrantLockWaitTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ReservationErrorCode.SEAT_LOCK_TIMEOUT, e);
        }
        if (!acquired) {
            throw new BusinessException(ReservationErrorCode.SEAT_LOCK_TIMEOUT);
        }
        try {
            return withReentrantLocks(seatIds, operation, index + 1);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 좌석별 MySQL named lock을 정렬 순서대로 획득한다.
     * 여러 애플리케이션 인스턴스가 공유하는 동시성 경계를 제공한다.
     */
    private Object withMysqlNamedLocks(List<Long> seatIds, ReservationLockOperation<Object> operation) throws Throwable {
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

    /**
     * GET_LOCK을 호출해 하나의 MySQL named lock을 획득한다.
     * 설정된 timeout 안에 성공했을 때만 true를 반환한다.
     */
    private boolean acquireNamedLock(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)");) {
            statement.setString(1, lockName);
            statement.setInt(2, namedLockTimeoutSeconds);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    /**
     * RELEASE_LOCK을 호출해 획득한 MySQL named lock을 해제한다.
     * 해제 실패는 진행 중인 원래 예약 예외를 덮어쓰지 않게 처리한다.
     */
    private void releaseNamedLock(Connection connection, String lockName) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, lockName);
            statement.executeQuery();
        } catch (SQLException ignored) {
            // 원래 예외가 있는 경우 release 실패가 원인을 덮어쓰지 않도록 한다.
        }
    }

    /**
     * 어노테이션에 지정된 lock 전략을 해석한다.
     * CONFIGURED 값이면 애플리케이션 설정의 전략을 반환한다.
     */
    private ReservationLockStrategy resolveStrategy(ReservationLockStrategy annotationStrategy) {
        if (annotationStrategy == ReservationLockStrategy.CONFIGURED) {
            return ReservationLockStrategy.from(configuredStrategy);
        }
        return annotationStrategy;
    }

    /**
     * AOP 대상 메서드 인자에서 예약 요청을 찾는다.
     * lock 대상 좌석을 추출할 수 없으면 null을 반환한다.
     */
    private ReservationRequest findRequest(Object[] arguments) {
        for (Object argument : arguments) {
            if (argument instanceof ReservationRequest request) {
                return request;
            }
        }
        return null;
    }

    /**
     * null과 중복 좌석을 제거하고 좌석 ID를 정렬한다.
     * 모든 lock 전략이 같은 획득 순서를 사용하게 한다.
     */
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
}

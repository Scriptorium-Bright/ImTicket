package org.example.ticket.reservation.booking.concurrency;

import org.aspectj.lang.ProceedingJoinPoint;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.booking.domain.ReservationErrorCode;
import org.example.ticket.reservation.booking.api.ReservationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservationLockAspectTest {

    private ThreadPoolTaskExecutor executor;
    private ReservationLockAspect aspect;
    private ReservationLockStrategyContext strategyContext;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("reservation-single-test-");
        executor.initialize();
        strategyContext = new ReservationLockStrategyContext();
        aspect = new ReservationLockAspect(mock(DataSource.class), executor, strategyContext);
        ReflectionTestUtils.setField(aspect, "configuredStrategy", "single-thread");
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void executesReservationOnDedicatedWorkerThread() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        ReservationRequest request = new ReservationRequest(1L, List.of(1L));
        AtomicReference<String> executionThread = new AtomicReference<>();

        when(joinPoint.getArgs()).thenReturn(new Object[]{request});
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            executionThread.set(Thread.currentThread().getName());
            return "reserved";
        });

        Object result = aspect.lockReservationSeats(joinPoint, configuredAnnotation());

        assertThat(result).isEqualTo("reserved");
        assertThat(executionThread).hasValueSatisfying(
                threadName -> assertThat(threadName).startsWith("reservation-single-test-")
        );
    }

    @Test
    void rejectsLockTargetWithoutReservationRequest() {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"0xmember"});

        assertThatThrownBy(() -> aspect.lockReservationSeats(joinPoint, configuredAnnotation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("@ReservationLock 대상 메서드에는 ReservationRequest 인자가 필요합니다.");
    }

    @Test
    void propagatesExplicitAnnotationStrategyToReservationCall() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        ReservationRequest request = new ReservationRequest(1L, List.of(1L));
        when(joinPoint.getArgs()).thenReturn(new Object[]{request});
        when(joinPoint.proceed()).thenAnswer(invocation -> strategyContext.currentStrategy().orElseThrow());

        ReservationLock annotation = mock(ReservationLock.class);
        when(annotation.strategy()).thenReturn(ReservationLockStrategy.PESSIMISTIC);

        Object result = aspect.lockReservationSeats(joinPoint, annotation);

        assertThat(result).isEqualTo(ReservationLockStrategy.PESSIMISTIC);
        assertThat(strategyContext.currentStrategy()).isEmpty();
    }

    @Test
    void reentrantLockTimesOutInsteadOfWaitingForever() throws Throwable {
        ReflectionTestUtils.setField(aspect, "reentrantLockWaitTimeoutMillis", 30L);
        ProceedingJoinPoint holder = mock(ProceedingJoinPoint.class);
        ProceedingJoinPoint contender = mock(ProceedingJoinPoint.class);
        ReservationRequest request = new ReservationRequest(1L, List.of(1L));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(holder.getArgs()).thenReturn(new Object[]{request});
        when(contender.getArgs()).thenReturn(new Object[]{request});
        when(holder.proceed()).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
            return "held";
        });

        ExecutorService holderExecutor = Executors.newSingleThreadExecutor();
        Future<Object> holderResult = holderExecutor.submit(() -> {
            try {
                return aspect.lockReservationSeats(holder, reentrantAnnotation());
            } catch (Throwable throwable) {
                throw new AssertionError(throwable);
            }
        });

        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            Throwable thrown = catchThrowable(() ->
                    aspect.lockReservationSeats(contender, reentrantAnnotation())
            );

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SEAT_LOCK_TIMEOUT);
        } finally {
            release.countDown();
            assertThat(holderResult.get(1, TimeUnit.SECONDS)).isEqualTo("held");
            holderExecutor.shutdownNow();
        }
    }

    @Test
    void releasesReentrantLockWhenReservationOperationFails() throws Throwable {
        ReflectionTestUtils.setField(aspect, "reentrantLockWaitTimeoutMillis", 30L);
        ProceedingJoinPoint failed = mock(ProceedingJoinPoint.class);
        ProceedingJoinPoint next = mock(ProceedingJoinPoint.class);
        ReservationRequest request = new ReservationRequest(1L, List.of(1L));

        when(failed.getArgs()).thenReturn(new Object[]{request});
        when(next.getArgs()).thenReturn(new Object[]{request});
        when(failed.proceed()).thenThrow(new IllegalStateException("reservation failed"));
        when(next.proceed()).thenReturn("next reservation");

        assertThatThrownBy(() -> aspect.lockReservationSeats(failed, reentrantAnnotation()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(aspect.lockReservationSeats(next, reentrantAnnotation()))
                .isEqualTo("next reservation");
    }

    private ReservationLock configuredAnnotation() {
        ReservationLock annotation = mock(ReservationLock.class);
        when(annotation.strategy()).thenReturn(ReservationLockStrategy.CONFIGURED);
        return annotation;
    }

    private ReservationLock reentrantAnnotation() {
        ReservationLock annotation = mock(ReservationLock.class);
        when(annotation.strategy()).thenReturn(ReservationLockStrategy.REENTRANT);
        return annotation;
    }
}

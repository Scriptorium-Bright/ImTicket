package org.example.ticket.reservation.lock;

import org.aspectj.lang.ProceedingJoinPoint;
import org.example.ticket.reservation.request.ReservationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private ReservationLock configuredAnnotation() {
        ReservationLock annotation = mock(ReservationLock.class);
        when(annotation.strategy()).thenReturn(ReservationLockStrategy.CONFIGURED);
        return annotation;
    }
}

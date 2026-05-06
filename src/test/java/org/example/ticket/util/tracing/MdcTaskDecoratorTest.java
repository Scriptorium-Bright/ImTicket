package org.example.ticket.util.tracing;

import org.example.ticket.reservation.service.SeatService;
import org.example.ticket.util.config.AsyncConfig;
import org.example.ticket.venue.service.VenueHallService;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MdcTaskDecoratorTest {

    private final TaskDecorator taskDecorator = new MdcTaskDecorator();

    @Test
    void copiesSubmitterMdcIntoAsyncTaskAndRestoresWorkerThreadContext() {
        MDC.put(TracingConstants.CORRELATION_ID_MDC_KEY, "request-correlation");

        Runnable decoratedTask = taskDecorator.decorate(() -> assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY))
                .isEqualTo("request-correlation"));

        MDC.put(TracingConstants.CORRELATION_ID_MDC_KEY, "worker-correlation");

        decoratedTask.run();

        assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)).isEqualTo("worker-correlation");
        MDC.clear();
    }

    @Test
    void clearsAsyncTaskMdcWhenSubmitterContextIsMissing() {
        Runnable decoratedTask = taskDecorator.decorate(() -> assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY))
                .isNull());

        MDC.put(TracingConstants.CORRELATION_ID_MDC_KEY, "worker-correlation");

        decoratedTask.run();

        assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)).isEqualTo("worker-correlation");
        MDC.clear();
    }

    @Test
    void propagatesMdcThroughSeatCreationTaskExecutor() throws InterruptedException {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AsyncConfig.class)) {
            Executor executor = context.getBean("seatCreationTaskExecutor", Executor.class);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> asyncCorrelationId = new AtomicReference<>();

            MDC.put(TracingConstants.CORRELATION_ID_MDC_KEY, "executor-correlation");

            executor.execute(() -> {
                asyncCorrelationId.set(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY));
                latch.countDown();
            });

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(asyncCorrelationId.get()).isEqualTo("executor-correlation");
            MDC.clear();
        }
    }

    @Test
    void keepsSeatAsyncPathsBoundToSeatCreationTaskExecutor() throws NoSuchMethodException {
        assertThat(asyncExecutorValue(VenueHallService.class, "allocateEmptySeatTemplate", Long.class, java.util.List.class))
                .isEqualTo("seatCreationTaskExecutor");
        assertThat(asyncExecutorValue(SeatService.class, "preprocessSeatData", Long.class))
                .isEqualTo("seatCreationTaskExecutor");
    }

    private String asyncExecutorValue(Class<?> targetClass, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = targetClass.getMethod(methodName, parameterTypes);
        Async async = method.getAnnotation(Async.class);

        assertThat(async).isNotNull();
        return async.value();
    }
}

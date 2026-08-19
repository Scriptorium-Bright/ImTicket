package org.example.ticket.config;

import org.example.ticket.util.tracing.MdcTaskDecorator;
import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public TaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }

    @Bean(name = "seatCreationTaskExecutor") // 스레드 풀에 고유한 이름을 부여
    public Executor taskExecutor(TaskDecorator mdcTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);   // 기본적으로 대기하는 스레드 수
        executor.setMaxPoolSize(20);    // 동시에 처리할 수 있는 최대 스레드 수
        executor.setQueueCapacity(100); // 최대 스레드가 모두 바쁠 때, 대기열에 쌓아둘 작업 수
        executor.setThreadNamePrefix("SeatCreate-");
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.initialize();
        return executor;
    }

    @Bean(name = "reservationSingleThreadTaskExecutor")
    public ThreadPoolTaskExecutor reservationSingleThreadTaskExecutor(
            TaskDecorator mdcTaskDecorator,
            @Value("${reservation.lock.single-thread.queue-capacity:1000}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("ReservationSingle-");
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.initialize();
        return executor;
    }

    @Bean(name = "waitingRoomJoinHandoffTaskExecutor")
    @ConditionalOnProperty(name = "reservation.waiting-room.async-join-enabled", havingValue = "true")
    public ThreadPoolTaskExecutor waitingRoomJoinHandoffTaskExecutor(
            TaskDecorator mdcTaskDecorator,
            WaitingRoomProperties properties,
            @Value("${reservation.waiting-room.join-handoff-worker-queue-capacity:1000}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getJoinHandoffWorkerConcurrency());
        executor.setMaxPoolSize(properties.getJoinHandoffWorkerConcurrency());
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("WaitingRoomJoin-");
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.initialize();
        return executor;
    }

}

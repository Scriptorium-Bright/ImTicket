package org.example.ticket.util.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "seatCreationTaskExecutor") // 스레드 풀에 고유한 이름을 부여
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);   // 기본적으로 대기하는 스레드 수
        executor.setMaxPoolSize(20);    // 동시에 처리할 수 있는 최대 스레드 수
        executor.setQueueCapacity(100); // 최대 스레드가 모두 바쁠 때, 대기열에 쌓아둘 작업 수
        executor.setThreadNamePrefix("SeatCreate-");
        executor.initialize();
        return executor;
    }
}
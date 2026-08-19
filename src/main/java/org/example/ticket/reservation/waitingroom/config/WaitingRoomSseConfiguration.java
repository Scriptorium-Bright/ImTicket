package org.example.ticket.reservation.waitingroom.config;

import org.example.ticket.reservation.waitingroom.sse.WaitingRoomLifecyclePublisher;
import org.example.ticket.reservation.waitingroom.sse.WaitingRoomLifecycleSubscriber;
import org.example.ticket.reservation.waitingroom.sse.WaitingRoomJoinHandoffLifecyclePublisher;
import org.example.ticket.reservation.waitingroom.sse.WaitingRoomJoinHandoffLifecycleSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.task.TaskDecorator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** Waiting Room SSE의 bounded delivery executor와 Redis Pub/Sub listener를 구성한다. */
@Configuration(proxyBeanMethods = false)
public class WaitingRoomSseConfiguration {

    /** SSE write를 Tomcat request thread와 분리하는 bounded executor를 등록한다.
     * connection별 pending 제한과 함께 delivery 폭주를 제어한다. */
    @Bean(name = "waitingRoomSseTaskExecutor")
    public ThreadPoolTaskExecutor waitingRoomSseTaskExecutor(
            WaitingRoomProperties properties,
            TaskDecorator mdcTaskDecorator
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getSseDeliveryCorePoolSize());
        executor.setMaxPoolSize(properties.getSseDeliveryMaxPoolSize());
        executor.setQueueCapacity(properties.getSseDeliveryQueueCapacity());
        executor.setThreadNamePrefix("WaitingRoomSse-");
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.initialize();
        return executor;
    }

    /** lifecycle event를 scheduler thread와 분리하는 bounded executor를 등록한다.
     * queue 포화 시 호출자 실행으로 전환해 notification 유실을 막고 backpressure를 적용한다. */
    @Bean(name = "waitingRoomLifecycleTaskExecutor")
    public ThreadPoolTaskExecutor waitingRoomLifecycleTaskExecutor(
            WaitingRoomProperties properties,
            TaskDecorator mdcTaskDecorator
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getLifecyclePublisherCorePoolSize());
        executor.setMaxPoolSize(properties.getLifecyclePublisherMaxPoolSize());
        executor.setQueueCapacity(properties.getLifecyclePublisherQueueCapacity());
        executor.setThreadNamePrefix("WaitingRoomLifecycle-");
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** 모든 application instance가 lifecycle channel을 구독하도록 Redis listener container를 등록한다.
     * ticket 상태 변경이 emitter를 가진 instance에 전달되도록 한다. */
    @Bean
    @ConditionalOnExpression("'${reservation.waiting-room.enabled:false}' == 'true' && '${ticket.application.role:reservation}' == 'waiting-room'")
    public RedisMessageListenerContainer waitingRoomSseRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            WaitingRoomLifecycleSubscriber subscriber
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(WaitingRoomLifecyclePublisher.CHANNEL));
        return container;
    }

    /** join handoff 완료 event를 모든 application instance에 전달한다.
     * request SSE connection이 worker instance와 달라도 완료 상태를 수신한다. */
    @Bean
    @ConditionalOnExpression("'${reservation.waiting-room.enabled:false}' == 'true' && '${ticket.application.role:reservation}' == 'waiting-room'")
    public RedisMessageListenerContainer waitingRoomJoinHandoffRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            WaitingRoomJoinHandoffLifecycleSubscriber subscriber
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(WaitingRoomJoinHandoffLifecyclePublisher.CHANNEL));
        return container;
    }
}

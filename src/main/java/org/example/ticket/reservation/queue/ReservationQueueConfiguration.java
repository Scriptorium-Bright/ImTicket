package org.example.ticket.reservation.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

/** Queue feature flag가 켜진 경우에만 Redis queue bean을 구성한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "reservation.queue", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ReservationQueueProperties.class)
public class ReservationQueueConfiguration {

    @Bean
    public ReservationQueueKeyFactory reservationQueueKeyFactory() {
        return new ReservationQueueKeyFactory();
    }

    @Bean
    public ReservationQueueIdentityHasher reservationQueueIdentityHasher() {
        return new ReservationQueueIdentityHasher();
    }

    @Bean
    public ReservationQueueAdmissionStore reservationQueueAdmissionStore(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory
    ) {
        return new RedisReservationQueueAdmissionStore(redisTemplate, properties, keyFactory);
    }

    @Bean
    public ReservationQueueTicketStore reservationQueueTicketStore(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory
    ) {
        return new RedisReservationQueueTicketStore(redisTemplate, properties, keyFactory);
    }

    @Bean
    public ReservationQueueExpiryIndex reservationQueueExpiryIndex(
            StringRedisTemplate redisTemplate,
            ReservationQueueKeyFactory keyFactory
    ) {
        return new RedisReservationQueueExpiryIndex(redisTemplate, keyFactory);
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock reservationQueueClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ReservationQueueService reservationQueueService(
            ReservationQueueAdmissionStore admissionStore,
            ReservationQueueTicketStore ticketStore,
            ReservationQueueIdentityHasher identityHasher,
            ReservationQueueProperties properties,
            Clock clock
    ) {
        return new ReservationQueueService(
                admissionStore,
                ticketStore,
                identityHasher,
                properties,
                clock
        );
    }

    @Bean
    public ReservationQueueExpiryService reservationQueueExpiryService(
            ReservationQueueExpiryIndex expiryIndex,
            ReservationQueueTicketStore ticketStore,
            ReservationQueueProperties properties
    ) {
        return new ReservationQueueExpiryService(expiryIndex, ticketStore, properties);
    }

    @Bean
    public ReservationQueueExpiryScheduler reservationQueueExpiryScheduler(
            ReservationQueueExpiryService expiryService,
            Clock clock
    ) {
        return new ReservationQueueExpiryScheduler(expiryService, clock);
    }
}

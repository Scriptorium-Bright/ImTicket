package org.example.ticket.reservation.queue.config;

import org.example.ticket.reservation.queue.service.ReservationQueueExpiryService;
import org.example.ticket.reservation.queue.util.ReservationQueueIdentityHasher;
import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.service.ReservationQueueService;
import org.example.ticket.reservation.queue.repository.ReservationQueueAdmissionStore;
import org.example.ticket.reservation.queue.repository.ReservationQueueExpiryIndex;
import org.example.ticket.reservation.queue.repository.ReservationQueueTicketStore;
import org.example.ticket.reservation.queue.repository.ReservationQueueWorkerStore;
import org.example.ticket.reservation.queue.repository.redis.RedisReservationQueueAdmissionStore;
import org.example.ticket.reservation.queue.repository.redis.RedisReservationQueueExpiryIndex;
import org.example.ticket.reservation.queue.repository.redis.RedisReservationQueueTicketStore;
import org.example.ticket.reservation.queue.repository.redis.RedisReservationQueueWorkerStore;
import org.example.ticket.reservation.queue.repository.redis.ReservationQueueKeyFactory;
import org.example.ticket.reservation.queue.util.scheduler.ReservationQueueExpiryScheduler;
import org.example.ticket.reservation.queue.util.worker.ReservationQueuePayloadV1Decoder;
import org.example.ticket.reservation.queue.util.worker.ReservationQueuePayloadVersionDecoder;
import org.example.ticket.reservation.queue.util.worker.ReservationQueueStreamPayloadDecoder;
import org.example.ticket.reservation.queue.util.worker.ReservationQueueWorkerPermits;
import org.example.ticket.reservation.common.policy.ReservationProcessingLeasePolicy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Queue feature flag가 켜진 경우에만 Redis queue bean을 구성한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "reservation.queue", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({ReservationQueueProperties.class, ReservationQueueWorkerProperties.class})
public class ReservationQueueConfiguration {

    /**
     * MySQL claim과 Redis processing lease의 공통 시간 정책을 구성한다.
     * Claim lease가 짧은 설정은 Queue Worker bean을 만들기 전에 시작 실패로 처리한다.
     */
    @Bean
    public ReservationProcessingLeasePolicy reservationProcessingLeasePolicy(
            ReservationQueueProperties properties,
            @Value("${reservation.idempotency.processing-lease-seconds:30}") long claimLeaseSeconds
    ) {
        return new ReservationProcessingLeasePolicy(
                Duration.ofSeconds(claimLeaseSeconds),
                properties.processingLease()
        );
    }

    /**
     * Queue Redis key 형식을 생성하는 factory를 등록한다.
     * 모든 저장소 구현이 같은 hash tag와 key 규칙을 공유한다.
     */
    @Bean
    public ReservationQueueKeyFactory reservationQueueKeyFactory() {
        return new ReservationQueueKeyFactory();
    }

    /**
     * wallet과 멱등 키를 정규화하는 hasher를 등록한다.
     * Controller 이후 Queue 서비스가 원문 식별자를 Redis key에 노출하지 않게 한다.
     */
    @Bean
    public ReservationQueueIdentityHasher reservationQueueIdentityHasher() {
        return new ReservationQueueIdentityHasher();
    }

    /**
     * Lua 기반 Queue 접수 저장소를 구성한다.
     * admission 한도와 공통 Redis key factory를 구현체에 전달한다.
     */
    @Bean
    public ReservationQueueAdmissionStore reservationQueueAdmissionStore(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory
    ) {
        return new RedisReservationQueueAdmissionStore(redisTemplate, properties, keyFactory);
    }

    /**
     * Queue ticket 상태 조회와 만료 전이를 담당할 저장소를 구성한다.
     * API 조회와 만료 서비스가 동일한 Redis ticket 계약을 사용한다.
     */
    @Bean
    public ReservationQueueTicketStore reservationQueueTicketStore(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory
    ) {
        return new RedisReservationQueueTicketStore(redisTemplate, properties, keyFactory);
    }

    /**
     * 만료 대상 회차와 ticket을 찾는 Redis index 저장소를 구성한다.
     * 스케줄러가 제한된 batch 단위로 due ticket을 읽을 수 있게 한다.
     */
    @Bean
    public ReservationQueueExpiryIndex reservationQueueExpiryIndex(
            StringRedisTemplate redisTemplate,
            ReservationQueueKeyFactory keyFactory
    ) {
        return new RedisReservationQueueExpiryIndex(redisTemplate, keyFactory);
    }

    /**
     * Queue 시간 계산에 사용할 UTC Clock을 기본으로 등록한다.
     * 테스트나 다른 설정이 Clock을 제공하면 기존 bean을 유지한다.
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock reservationQueueClock() {
        return Clock.systemUTC();
    }

    /**
     * Queue 접수와 ticket 조회를 조율하는 서비스를 구성한다.
     * Redis 저장소, 식별자 정규화와 시간 의존성을 한곳에서 연결한다.
     */
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

    /**
     * due ticket을 만료 상태로 전환하는 서비스를 구성한다.
     * 만료 index와 ticket 저장소에 동일한 Queue 설정을 전달한다.
     */
    @Bean
    public ReservationQueueExpiryService reservationQueueExpiryService(
            ReservationQueueExpiryIndex expiryIndex,
            ReservationQueueTicketStore ticketStore,
            ReservationQueueProperties properties
    ) {
        return new ReservationQueueExpiryService(expiryIndex, ticketStore, properties);
    }

    /**
     * 주기적으로 Queue 만료 서비스를 호출하는 scheduler를 구성한다.
     * 주입한 Clock으로 각 scan의 기준 시각을 결정한다.
     */
    @Bean
    public ReservationQueueExpiryScheduler reservationQueueExpiryScheduler(
            ReservationQueueExpiryService expiryService,
            Clock clock
    ) {
        return new ReservationQueueExpiryScheduler(expiryService, clock);
    }

    /**
     * Queue payload schema v1 decoder를 Worker registry에 등록한다.
     * 지원 version별 구현을 분리해 schema 추가가 기존 decoder를 바꾸지 않게 한다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "reservation.queue.worker", name = "enabled", havingValue = "true")
    public ReservationQueuePayloadVersionDecoder reservationQueuePayloadV1Decoder() {
        return new ReservationQueuePayloadV1Decoder();
    }

    /**
     * 등록된 version decoder를 Stream payload registry로 구성한다.
     * 중복 schema version은 registry 생성 단계에서 시작 실패로 처리된다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "reservation.queue.worker", name = "enabled", havingValue = "true")
    public ReservationQueueStreamPayloadDecoder reservationQueueStreamPayloadDecoder(
            List<ReservationQueuePayloadVersionDecoder> decoders
    ) {
        return new ReservationQueueStreamPayloadDecoder(decoders);
    }

    /**
     * Consumer Group 읽기와 PROCESSING claim을 수행하는 Redis 저장소를 등록한다.
     * Admission 저장소와 같은 template, 보존 설정과 key factory를 공유한다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "reservation.queue.worker", name = "enabled", havingValue = "true")
    public ReservationQueueWorkerStore reservationQueueWorkerStore(
            StringRedisTemplate redisTemplate,
            ReservationQueueProperties properties,
            ReservationQueueKeyFactory keyFactory
    ) {
        return new RedisReservationQueueWorkerStore(redisTemplate, properties, keyFactory);
    }

    /**
     * 전체와 회차별 Worker 동시 처리 permit 관리자를 등록한다.
     * Stream 읽기 전에 두 상한을 함께 적용해 처리량 초과 entry 선점을 막는다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "reservation.queue.worker", name = "enabled", havingValue = "true")
    public ReservationQueueWorkerPermits reservationQueueWorkerPermits(
            ReservationQueueWorkerProperties properties
    ) {
        return new ReservationQueueWorkerPermits(
                properties.concurrency(),
                properties.perPerformanceConcurrency()
        );
    }

    /**
     * Worker 동시성 수와 같은 고정 thread 수의 executor를 등록한다.
     * SynchronousQueue와 AbortPolicy를 사용해 executor 내부 대기 작업을 허용하지 않는다.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "reservation.queue.worker", name = "enabled", havingValue = "true")
    public ThreadPoolExecutor reservationQueueWorkerExecutor(
            ReservationQueueWorkerProperties properties
    ) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task);
            thread.setName("reservation-queue-worker-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
        return new ThreadPoolExecutor(
                properties.concurrency(),
                properties.concurrency(),
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}

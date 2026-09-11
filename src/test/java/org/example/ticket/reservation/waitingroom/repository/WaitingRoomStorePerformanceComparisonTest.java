package org.example.ticket.reservation.waitingroom.repository;

import org.example.ticket.reservation.waitingroom.repository.inmemory.InMemoryWaitingRoomStore;
import org.example.ticket.reservation.waitingroom.repository.redis.RedisWaitingRoomStore;
import org.example.ticket.reservation.waitingroom.repository.redis.WaitingRoomKeyFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 WaitingRoomStore 계약을 메모리 구현과 Redis 구현에 적용하는 방향성 비교 시험이다.
 * 기본 실행은 메모리만 측정하고, Redis 비교는 전용 Redis를 띄운 뒤 환경 변수로 활성화한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaitingRoomStorePerformanceComparisonTest {

    private static final int USER_COUNT = 2_000;
    private static final int WORKER_COUNT = 32;
    private static final int PROMOTION_BATCH = 50;
    private static final long PERFORMANCE_TIME_ID = 9_101L;
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private static final Duration ENTRY_LEASE = Duration.ofMinutes(5);
    private static final Duration RETENTION = Duration.ofHours(1);
    private final List<UUID> ticketIds = ticketIds();

    private LettuceConnectionFactory connectionFactory;

    /** 메모리 대안의 2,000명 join·status·promotion 기준선을 출력한다. */
    @Test
    void measuresInMemoryStoreForTheSameWaitingRoomWorkload() throws Exception {
        BenchmarkReport report = benchmark("memory", new InMemoryWaitingRoomStore(), PERFORMANCE_TIME_ID);
        assertThat(report.join().count()).isEqualTo(USER_COUNT);
        assertThat(report.status().count()).isEqualTo(USER_COUNT);
        assertThat(report.promotion().count()).isEqualTo((USER_COUNT + PROMOTION_BATCH - 1) / PROMOTION_BATCH);
        printReport(report);
    }

    /** 전용 Redis가 켜진 경우 같은 workload의 Redis 결과를 메모리 결과와 함께 출력한다. */
    @Test
    @EnabledIfEnvironmentVariable(named = "WAITING_ROOM_REDIS_BENCHMARK", matches = "(?i)true")
    void comparesRedisStoreWithTheSameWaitingRoomWorkload() throws Exception {
        String host = environment("WAITING_ROOM_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(environment("WAITING_ROOM_REDIS_PORT", "16380"));
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        BenchmarkReport memory = benchmark("memory", new InMemoryWaitingRoomStore(), PERFORMANCE_TIME_ID + 1);
        BenchmarkReport redis = benchmark(
                "redis",
                new RedisWaitingRoomStore(redisTemplate, new WaitingRoomKeyFactory()),
                PERFORMANCE_TIME_ID + 2
        );
        printReport(memory, redis);
        assertThat(redis.join().count()).isEqualTo(USER_COUNT);
        assertThat(redis.status().count()).isEqualTo(USER_COUNT);
        assertThat(redis.promotion().count()).isEqualTo((USER_COUNT + PROMOTION_BATCH - 1) / PROMOTION_BATCH);
    }

    /** Redis benchmark connection을 종료한다. */
    @AfterAll
    void closeRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private BenchmarkReport benchmark(String name, WaitingRoomStore store, long performanceTimeId)
            throws Exception {
        Metric join = measureConcurrent(USER_COUNT, index -> store.join(
                performanceTimeId,
                100_000L + index,
                ticketIds.get(index),
                NOW,
                NOW.plus(Duration.ofMinutes(30)),
                RETENTION,
                USER_COUNT + 10
        ));
        Metric status = measureConcurrent(USER_COUNT, index -> {
            store.find(performanceTimeId, ticketIds.get(index));
            store.waitingRank(performanceTimeId, ticketIds.get(index));
        });

        int promotionCount = (USER_COUNT + PROMOTION_BATCH - 1) / PROMOTION_BATCH;
        List<Long> promotionSamples = new ArrayList<>(promotionCount);
        long promotionStart = System.nanoTime();
        for (int index = 0; index < promotionCount; index++) {
            long operationStart = System.nanoTime();
            store.promote(
                    performanceTimeId,
                    NOW.plusSeconds(index),
                    ENTRY_LEASE,
                    USER_COUNT,
                    PROMOTION_BATCH,
                    Duration.ofSeconds(1),
                    RETENTION
            );
            promotionSamples.add(System.nanoTime() - operationStart);
        }
        Metric promotion = new Metric(promotionCount, System.nanoTime() - promotionStart, promotionSamples);
        return new BenchmarkReport(name, join, status, promotion);
    }

    private Metric measureConcurrent(int operationCount, IndexedOperation operation)
            throws InterruptedException, ExecutionException {
        ExecutorService executor = Executors.newFixedThreadPool(WORKER_COUNT);
        int workerCount = Math.min(WORKER_COUNT, operationCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(workerCount);
        List<Long> samples = Collections.synchronizedList(new ArrayList<>(operationCount));
        try {
            for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
                int assignedWorker = workerIndex;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int operationIndex = assignedWorker;
                         operationIndex < operationCount;
                         operationIndex += workerCount) {
                        long operationStart = System.nanoTime();
                        operation.run(operationIndex);
                        samples.add(System.nanoTime() - operationStart);
                    }
                    return null;
                }));
            }
            long wallStart = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            return new Metric(operationCount, System.nanoTime() - wallStart, samples);
        } finally {
            executor.shutdownNow();
        }
    }

    private void printReport(BenchmarkReport... reports) {
        System.out.println("[Waiting Room store comparison: users=" + USER_COUNT
                + ", workers=" + WORKER_COUNT + "]");
        System.out.println("| store | operation | count | p50 us | p95 us | throughput op/s |");
        System.out.println("|---|---:|---:|---:|---:|---:|");
        for (BenchmarkReport report : reports) {
            printMetric(report.name(), "join", report.join());
            printMetric(report.name(), "status", report.status());
            printMetric(report.name(), "promotion", report.promotion());
        }
    }

    private void printMetric(String store, String operation, Metric metric) {
        System.out.printf(
                "| %s | %s | %d | %.2f | %.2f | %.2f |%n",
                store,
                operation,
                metric.count(),
                metric.percentileMicros(0.50),
                metric.percentileMicros(0.95),
                metric.throughput()
        );
    }

    private List<UUID> ticketIds() {
        List<UUID> ids = new ArrayList<>(USER_COUNT);
        for (int index = 0; index < USER_COUNT; index++) {
            ids.add(UUID.nameUUIDFromBytes(("waiting-room-benchmark-" + index).getBytes()));
        }
        return Collections.unmodifiableList(ids);
    }

    private String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    @FunctionalInterface
    private interface IndexedOperation {
        void run(int index) throws Exception;
    }

    private record BenchmarkReport(String name, Metric join, Metric status, Metric promotion) {
    }

    private record Metric(int count, long wallNanos, List<Long> samples) {
        private Metric {
            samples = new ArrayList<>(samples);
            samples.sort(Long::compareTo);
        }

        private double percentileMicros(double percentile) {
            int index = (int) Math.ceil(percentile * samples.size()) - 1;
            return samples.get(Math.max(0, Math.min(index, samples.size() - 1))) / 1_000.0;
        }

        private double throughput() {
            return count / (wallNanos / 1_000_000_000.0);
        }
    }
}

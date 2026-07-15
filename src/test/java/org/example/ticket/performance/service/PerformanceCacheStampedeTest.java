package org.example.ticket.performance.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.ticket.member.repository.OrganizerRepository;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.repository.PerformanceRepository;
import org.example.ticket.performance.response.PerformanceDetailsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PerformanceCacheStampedeTest {

    @Test
    @DisplayName("동일 key의 동시 cache miss는 DB 조회와 Redis write를 한 번으로 합친다")
    void coalescesConcurrentCacheMissesIntoOneDatabaseReadAndCacheWrite() throws Exception {
        int requestCount = 32;
        Long performanceId = 1L;
        String cacheKey = "performance:details:" + performanceId;

        PerformanceRepository performanceRepository = mock(PerformanceRepository.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        CountDownLatch workersReady = new CountDownLatch(requestCount);
        CountDownLatch startRequests = new CountDownLatch(1);
        CountDownLatch cacheMissesObserved = new CountDownLatch(requestCount);
        CountDownLatch databaseReadStarted = new CountDownLatch(1);
        CountDownLatch releaseDatabaseRead = new CountDownLatch(1);
        AtomicInteger databaseReads = new AtomicInteger();
        AtomicReference<PerformanceDetailsResponse> cachedValue = new AtomicReference<>();

        when(valueOperations.get(cacheKey)).thenAnswer(invocation -> {
            cacheMissesObserved.countDown();
            if (!cacheMissesObserved.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("모든 요청이 cache miss 지점에 도달하지 못했습니다.");
            }
            return cachedValue.get();
        });
        doAnswer(invocation -> {
            cachedValue.set(invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(
                eq(cacheKey),
                any(),
                eq((long) PerformanceService.CACHE_TIMEOUT),
                eq(PerformanceService.MINUTES)
        );
        when(performanceRepository.findById(any())).thenAnswer(invocation -> {
            databaseReads.incrementAndGet();
            databaseReadStarted.countDown();
            if (!releaseDatabaseRead.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("DB 조회 해제 대기 시간이 초과되었습니다.");
            }
            return Optional.of(Performance.builder().title("Test").build());
        });

        PerformanceService performanceService = new PerformanceService(
                performanceRepository,
                mock(OrganizerRepository.class),
                mock(FileService.class),
                redisTemplate,
                new SimpleMeterRegistry()
        );
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            List<Future<?>> requests = new ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                requests.add(executor.submit(() -> {
                    workersReady.countDown();
                    if (!startRequests.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 시작 대기 시간이 초과되었습니다.");
                    }
                    return performanceService.viewPerformanceDetailsCached(performanceId);
                }));
            }

            assertThat(workersReady.await(5, TimeUnit.SECONDS)).isTrue();
            startRequests.countDown();
            assertThat(databaseReadStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseDatabaseRead.countDown();

            for (Future<?> request : requests) {
                assertThat(request.get(5, TimeUnit.SECONDS)).isNotNull();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(databaseReads.get()).isEqualTo(1);
        verify(performanceRepository, times(1)).findById(performanceId);
        verify(valueOperations, times(1)).set(
                eq(cacheKey),
                any(),
                eq((long) PerformanceService.CACHE_TIMEOUT),
                eq(PerformanceService.MINUTES)
        );
    }

    @Test
    @DisplayName("기존 miss를 늦게 처리해 owner가 된 요청은 DB 전에 Redis를 다시 확인한다")
    void rechecksRedisAfterAcquiringFlightToAvoidLateMissDuplicateLoad() {
        Long performanceId = 1L;
        String cacheKey = "performance:details:" + performanceId;
        PerformanceDetailsResponse cachedResponse = PerformanceDetailsResponse.builder()
                .title("Cached")
                .build();

        PerformanceRepository performanceRepository = mock(PerformanceRepository.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(cacheKey)).thenReturn(null, cachedResponse);

        PerformanceService performanceService = new PerformanceService(
                performanceRepository,
                mock(OrganizerRepository.class),
                mock(FileService.class),
                redisTemplate,
                new SimpleMeterRegistry()
        );

        PerformanceDetailsResponse response = performanceService.viewPerformanceDetailsCached(performanceId);

        assertThat(response).isSameAs(cachedResponse);
        verify(performanceRepository, never()).findById(any());
        verify(valueOperations, never()).set(
                eq(cacheKey),
                any(),
                eq((long) PerformanceService.CACHE_TIMEOUT),
                eq(PerformanceService.MINUTES)
        );
    }
}

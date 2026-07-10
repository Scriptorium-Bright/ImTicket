package org.example.ticket.performance.service;

import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.repository.PerformanceRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
public class PerformanceCacheStampedeTest {

    @Autowired
    private PerformanceService performanceService;

    @MockBean
    private PerformanceRepository performanceRepository;

    @Autowired
    private CacheManager cacheManager;

    @Test
    @Disabled("캐시 스탬피드 개선 시나리오 테스트는 Redis/future 구현과 함께 별도 활성화한다.")
    @DisplayName("시나리오 5: 캐시 스탬피드 - 여러 스레드가 동시에 캐시 미스 상태에서 조회할 때 DB 쿼리는 한 번만 나가야 한다")
    public void testCacheStampede() throws InterruptedException {
        // given
        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        Long performanceId = 1L;

        // DB는 정상 응답을 반환한다고 Mocking
        when(performanceRepository.findById(any()))
                .thenReturn(Optional.of(Performance.builder().title("Test").build()));

        // 캐시 비우기 (캐시 미스 상황)
        if (cacheManager.getCache("performanceDetails") != null) {
            cacheManager.getCache("performanceDetails").clear();
        }

        // when
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    performanceService.viewPerformanceDetailsCached(performanceId);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // then
        // Single Flight 패턴이나 @Cacheable(sync=true) 등이 적용되어 있다면
        // DB 쿼리는 정확히 1번만 나갔어야 함
        // (현재 개선되지 않았다면 100번에 가깝게 나갈 수 있음)
        verify(performanceRepository, times(1)).findById(performanceId);
    }
}

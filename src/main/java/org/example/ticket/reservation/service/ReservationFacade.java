package org.example.ticket.reservation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.reservation.request.ReservationRequest;
import org.example.ticket.reservation.response.ReservationCreateResponse;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationFacade {

    private final RedissonClient redissonClient;
    private final ReservationService reservationService; // 실제 비즈니스 로직을 담고 있는 서비스

//    @Around("@annotation(org.example.ticket.util.annotation.DistributedLock)")
    public ReservationCreateResponse createReservationWithLock(String walletAddress, ReservationRequest request) {
        String lockKey = "performanceTime_lock:" + request.getPerformanceTimeId();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 락 획득 시도 (waitTime: 15초, leaseTime: 1초)
            // leaseTime을 짧게 주어 락을 가진 스레드가 비정상 종료되어도 락이 자동으로 해제되도록 보장
            boolean available = lock.tryLock(15, 1, TimeUnit.SECONDS);

            if (!available) {
                log.warn("락 획득 실패. lockKey={}", lockKey);
                // 락 획득 실패 시 명시적 예외 발생 또는 비즈니스 응답 처리
                throw new IllegalStateException("현재 다른 요청을 처리 중입니다. 잠시 후 다시 시도해주세요.");
            }

            // 락을 획득한 상태에서 트랜잭션이 적용된 실제 비즈니스 로직 호출
            reservationService.createReservationWithDistribution(walletAddress, request);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("락 대기 중 인터럽트가 발생했습니다.", e);
        } finally {
            // 현재 스레드가 락을 점유하고 있을 경우에만 락을 해제
            if (lock.isLocked() && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        return null;
    }
}
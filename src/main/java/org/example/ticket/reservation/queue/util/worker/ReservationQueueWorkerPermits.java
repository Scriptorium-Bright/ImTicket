package org.example.ticket.reservation.queue.util.worker;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stream 읽기 전에 전체와 회차별 처리량을 함께 제한하는 local permit 관리자다. */
public final class ReservationQueueWorkerPermits {

    private final Semaphore global;
    private final int perPerformanceLimit;
    private final ConcurrentHashMap<Long, Semaphore> performancePermits = new ConcurrentHashMap<>();

    /**
     * 전체 Worker 상한과 한 회차가 사용할 수 있는 상한을 구성한다.
     * 회차별 상한은 전체 상한보다 클 수 없다.
     */
    public ReservationQueueWorkerPermits(int globalLimit, int perPerformanceLimit) {
        if (globalLimit <= 0 || perPerformanceLimit <= 0 || perPerformanceLimit > globalLimit) {
            throw new IllegalArgumentException("Worker permit limits are invalid");
        }
        this.global = new Semaphore(globalLimit, true);
        this.perPerformanceLimit = perPerformanceLimit;
    }

    /**
     * 전체 permit을 먼저 얻고 해당 회차 permit을 이어서 시도한다.
     * 둘 중 하나라도 얻지 못하면 어떤 permit도 보유하지 않은 결과를 반환한다.
     */
    public Optional<Permit> tryAcquire(long performanceTimeId) {
        if (performanceTimeId <= 0) {
            throw new IllegalArgumentException("performanceTimeId must be positive");
        }
        if (!global.tryAcquire()) {
            return Optional.empty();
        }
        Semaphore performance = performancePermits.computeIfAbsent(
                performanceTimeId,
                ignored -> new Semaphore(perPerformanceLimit, true)
        );
        if (!performance.tryAcquire()) {
            global.release();
            return Optional.empty();
        }
        return Optional.of(new Permit(global, performance));
    }

    /**
     * 현재 즉시 사용할 수 있는 전체 permit 수를 반환한다.
     * 단위 테스트와 종료 점검에서 permit 누수를 확인할 때 사용한다.
     */
    public int availableGlobalPermits() {
        return global.availablePermits();
    }

    /** 읽기부터 처리 종료까지 보유한 두 semaphore permit의 반환 handle이다. */
    public static final class Permit implements AutoCloseable {

        private final Semaphore global;
        private final Semaphore performance;
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * 이미 획득한 전체와 회차 semaphore를 반환 handle에 연결한다.
         * 생성은 외부에 공개하지 않고 관리자만 수행한다.
         */
        private Permit(Semaphore global, Semaphore performance) {
            this.global = global;
            this.performance = performance;
        }

        /**
         * 회차 permit과 전체 permit을 한 번만 반환한다.
         * 중복 close가 semaphore 상한을 늘리지 않도록 원자 flag로 보호한다.
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                performance.release();
                global.release();
            }
        }
    }
}

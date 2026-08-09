package org.example.ticket.reservation.booking.util.lock;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * annotation에서 해석한 전략을 같은 예매 호출 경로의 하위 계층에 전달한다.
 */
@Component
public class ReservationLockStrategyContext {

    private final ThreadLocal<ReservationLockStrategy> currentStrategy = new ThreadLocal<>();

    /**
     * 현재 예약 호출 스레드에 적용된 lock 전략을 조회한다.
     * AOP 경계 밖에서는 빈 Optional을 반환한다.
     */
    public Optional<ReservationLockStrategy> currentStrategy() {
        return Optional.ofNullable(currentStrategy.get());
    }

    /**
     * 작업 실행 동안 현재 스레드에 선택한 lock 전략을 설정한다.
     * 작업이 끝나면 이전 context를 복원하거나 ThreadLocal을 제거한다.
     */
    public <T> T withStrategy(ReservationLockStrategy strategy, ReservationLockOperation<T> operation)
            throws Throwable {
        ReservationLockStrategy previous = currentStrategy.get();
        currentStrategy.set(strategy);
        try {
            return operation.run();
        } finally {
            if (previous == null) {
                currentStrategy.remove();
            } else {
                currentStrategy.set(previous);
            }
        }
    }
}

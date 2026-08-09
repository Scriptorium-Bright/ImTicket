package org.example.ticket.reservation.booking.concurrency;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * annotation에서 해석한 전략을 같은 예매 호출 경로의 하위 계층에 전달한다.
 */
@Component
public class ReservationLockStrategyContext {

    private final ThreadLocal<ReservationLockStrategy> currentStrategy = new ThreadLocal<>();

    /** 현재 예매 호출 스레드에 적용된 lock 전략을 조회한다. */
    public Optional<ReservationLockStrategy> currentStrategy() {
        return Optional.ofNullable(currentStrategy.get());
    }

    /** 하위 계층이 현재 lock 전략을 조회할 수 있도록 잠시 설정하고 호출 뒤 원래 값으로 복원한다. */
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

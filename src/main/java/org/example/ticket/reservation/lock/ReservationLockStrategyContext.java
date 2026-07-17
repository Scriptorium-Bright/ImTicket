package org.example.ticket.reservation.lock;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * annotation에서 해석한 전략을 같은 예매 호출 경로의 하위 계층에 전달한다.
 */
@Component
public class ReservationLockStrategyContext {

    private final ThreadLocal<ReservationLockStrategy> currentStrategy = new ThreadLocal<>();

    public Optional<ReservationLockStrategy> currentStrategy() {
        return Optional.ofNullable(currentStrategy.get());
    }

    public <T> T withStrategy(ReservationLockStrategy strategy, ThrowingOperation<T> operation)
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

    @FunctionalInterface
    public interface ThrowingOperation<T> {
        T run() throws Throwable;
    }
}

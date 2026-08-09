package org.example.ticket.reservation.booking.util.admission;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 좌석 admission 동안 획득한 permit을 보유하고 close 시 한 번만 반환한다. */
public final class SeatAdmission implements AutoCloseable {

    private final Runnable releaseAction;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 획득한 모든 좌석 permit을 반환할 동작을 보관한다.
     * close 호출 시 이 동작이 최대 한 번 실행된다.
     */
    SeatAdmission(Runnable releaseAction) {
        this.releaseAction = Objects.requireNonNull(releaseAction, "releaseAction은 필수입니다.");
    }

    /**
     * admission 범위에서 획득한 permit을 반환한다.
     * 여러 번 호출돼도 반환 동작은 한 번만 수행된다.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            releaseAction.run();
        }
    }
}

package org.example.ticket.reservation.admission;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 좌석 admission 동안 획득한 permit을 보유하고 close 시 한 번만 반환한다. */
public final class SeatAdmission implements AutoCloseable {

    private final Runnable releaseAction;
    private final AtomicBoolean closed = new AtomicBoolean();

    SeatAdmission(Runnable releaseAction) {
        this.releaseAction = Objects.requireNonNull(releaseAction, "releaseAction은 필수입니다.");
    }

    @Override
    /** 여러 번 호출돼도 획득한 permit은 한 번만 반환한다. */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            releaseAction.run();
        }
    }
}

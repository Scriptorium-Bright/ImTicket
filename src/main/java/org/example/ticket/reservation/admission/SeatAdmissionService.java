package org.example.ticket.reservation.admission;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.exception.ReservationErrorCode;
import org.example.ticket.reservation.request.ReservationRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 좌석 예매 요청이 JVM-local lock 대기열에 들어가기 전에 즉시 admission을 판정한다.
 *
 * <p>다중 좌석 요청은 모든 seat permit을 확보한 경우에만 통과한다. 하나라도 이미 사용 중이면
 * 먼저 확보한 permit을 즉시 반납하고 기다리지 않고 거절한다.</p>
 */
@Component
public class SeatAdmissionService {

    private final int permitsPerSeat;
    private final ConcurrentMap<Long, SeatSlot> slots = new ConcurrentHashMap<>();

    public SeatAdmissionService(
            @Value("${reservation.admission.per-seat-permits:1}") int permitsPerSeat
    ) {
        if (permitsPerSeat < 1) {
            throw new IllegalArgumentException("reservation.admission.per-seat-permits는 1 이상이어야 합니다.");
        }
        this.permitsPerSeat = permitsPerSeat;
    }

    public <T> T execute(ReservationRequest request, Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation은 필수입니다.");
        try (SeatAdmission admission = admit(request)) {
            return operation.get();
        }
    }

    public SeatAdmission admit(ReservationRequest request) {
        List<Long> seatIds = normalizeSeatIds(request);
        List<HeldSeat> acquired = new ArrayList<>(seatIds.size());

        for (Long seatId : seatIds) {
            SeatSlot slot = tryAcquire(seatId);
            if (slot == null) {
                releaseAll(acquired);
                throw new BusinessException(ReservationErrorCode.SEAT_ADMISSION_REJECTED);
            }
            acquired.add(new HeldSeat(seatId, slot));
        }

        return new SeatAdmission(acquired);
    }

    private List<Long> normalizeSeatIds(ReservationRequest request) {
        if (request == null || request.getSeatIds() == null || request.getSeatIds().isEmpty()) {
            return List.of();
        }
        return request.getSeatIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private SeatSlot tryAcquire(Long seatId) {
        AtomicReference<SeatSlot> acquired = new AtomicReference<>();
        slots.compute(seatId, (ignored, current) -> {
            SeatSlot slot = current == null ? new SeatSlot(permitsPerSeat) : current;
            if (slot.permits.tryAcquire()) {
                acquired.set(slot);
            }
            return slot;
        });
        return acquired.get();
    }

    private void releaseAll(List<HeldSeat> acquired) {
        for (int index = acquired.size() - 1; index >= 0; index--) {
            release(acquired.get(index));
        }
    }

    private void release(HeldSeat heldSeat) {
        slots.compute(heldSeat.seatId(), (ignored, current) -> {
            if (current != heldSeat.slot()) {
                throw new IllegalStateException("좌석 admission permit 상태가 일치하지 않습니다.");
            }
            heldSeat.slot().permits.release();
            return heldSeat.slot().permits.availablePermits() == permitsPerSeat ? null : heldSeat.slot();
        });
    }

    public final class SeatAdmission implements AutoCloseable {

        private final List<HeldSeat> acquired;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SeatAdmission(List<HeldSeat> acquired) {
            this.acquired = List.copyOf(acquired);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                releaseAll(acquired);
            }
        }
    }

    private record HeldSeat(Long seatId, SeatSlot slot) {
    }

    private static final class SeatSlot {

        private final Semaphore permits;

        private SeatSlot(int permitsPerSeat) {
            this.permits = new Semaphore(permitsPerSeat, true);
        }
    }
}

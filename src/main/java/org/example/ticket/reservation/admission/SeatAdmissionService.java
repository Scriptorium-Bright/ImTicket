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
    private final ConcurrentMap<Long, SeatAdmissionSlot> slots = new ConcurrentHashMap<>();

    /** 좌석 하나에 동시에 진입시킬 최대 요청 수를 검증하고 admission 상태를 초기화한다. */
    public SeatAdmissionService(
            @Value("${reservation.admission.per-seat-permits:1}") int permitsPerSeat
    ) {
        if (permitsPerSeat < 1) {
            throw new IllegalArgumentException("reservation.admission.per-seat-permits는 1 이상이어야 합니다.");
        }
        this.permitsPerSeat = permitsPerSeat;
    }

    /** 모든 대상 좌석의 permit을 확보한 동안에만 예약 작업을 실행하고 종료 시 자동 반납한다. */
    public <T> T execute(ReservationRequest request, Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation은 필수입니다.");
        try (SeatAdmission admission = admit(request)) {
            // admission은 명시적으로 읽지 않아도 블록 종료 시 close()가 permit을 반드시 반납한다.
            return operation.get();
        }
    }

    /** 여러 좌석 permit을 기다리지 않고 모두 확보하거나, 하나라도 실패하면 이미 확보한 permit을 즉시 되돌린다. */
    public SeatAdmission admit(ReservationRequest request) {
        List<Long> seatIds = normalizeSeatIds(request);
        List<SeatAdmissionPermit> acquired = new ArrayList<>(seatIds.size());

        for (Long seatId : seatIds) {
            SeatAdmissionSlot slot = tryAcquire(seatId);
            if (slot == null) {
                releaseAll(acquired);
                throw new BusinessException(ReservationErrorCode.SEAT_ADMISSION_REJECTED);
            }
            acquired.add(new SeatAdmissionPermit(seatId, slot));
        }

        List<SeatAdmissionPermit> heldPermits = List.copyOf(acquired);
        return new SeatAdmission(() -> releaseAll(heldPermits));
    }

    /** null/중복 좌석을 제거하고 정렬해 다중 좌석 admission 순서를 일관되게 만든다. */
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

    /** 좌석별 Semaphore를 원자적으로 조회·생성한 뒤 permit을 비차단 방식으로 하나 획득한다. */
    private SeatAdmissionSlot tryAcquire(Long seatId) {
        AtomicReference<SeatAdmissionSlot> acquired = new AtomicReference<>();
        slots.compute(seatId, (ignored, current) -> {
            SeatAdmissionSlot slot = current == null ? new SeatAdmissionSlot(permitsPerSeat) : current;
            if (slot.tryAcquire()) {
                acquired.set(slot);
            }
            return slot;
        });
        return acquired.get();
    }

    /** 부분 획득 실패 또는 작업 종료 시 보유한 permit을 역순으로 모두 반납한다. */
    private void releaseAll(List<SeatAdmissionPermit> acquired) {
        for (int index = acquired.size() - 1; index >= 0; index--) {
            release(acquired.get(index));
        }
    }

    /** 하나의 좌석 permit을 반납하고 더 이상 사용자가 없으면 해당 좌석의 admission slot을 제거한다. */
    private void release(SeatAdmissionPermit heldSeat) {
        slots.compute(heldSeat.seatId(), (ignored, current) -> {
            if (current != heldSeat.slot()) {
                throw new IllegalStateException("좌석 admission permit 상태가 일치하지 않습니다.");
            }
            heldSeat.slot().release();
            return heldSeat.slot().isFullyReleased(permitsPerSeat) ? null : heldSeat.slot();
        });
    }

}

package org.example.ticket.reservation.booking.util.admission;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.dto.request.ReservationRequest;
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

    /**
     * 좌석 하나에 허용할 최대 동시 요청 수를 검증한다.
     * 좌석별 admission slot은 실제 요청이 들어올 때 지연 생성한다.
     */
    public SeatAdmissionService(
            @Value("${reservation.admission.per-seat-permits:1}") int permitsPerSeat
    ) {
        if (permitsPerSeat < 1) {
            throw new IllegalArgumentException("reservation.admission.per-seat-permits는 1 이상이어야 합니다.");
        }
        this.permitsPerSeat = permitsPerSeat;
    }

    /**
     * 모든 대상 좌석 permit을 확보한 범위에서 예약 작업을 실행한다.
     * 작업 성공이나 예외와 관계없이 close에서 permit을 반환한다.
     */
    public <T> T execute(ReservationRequest request, Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation은 필수입니다.");
        try (SeatAdmission admission = admit(request)) {
            // admission은 명시적으로 읽지 않아도 블록 종료 시 close()가 permit을 반드시 반납한다.
            return operation.get();
        }
    }

    /**
     * 여러 좌석 permit을 기다리지 않고 정렬 순서로 획득한다.
     * 하나라도 실패하면 이미 획득한 permit을 반환하고 요청을 거절한다.
     */
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

    /**
     * null과 중복 좌석을 제거하고 좌석 ID를 정렬한다.
     * 다중 좌석 admission이 항상 같은 획득 순서를 사용하게 한다.
     */
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

    /**
     * 좌석별 semaphore slot을 원자적으로 조회하거나 생성한다.
     * 대기 없이 permit 한 개를 획득하고 실패하면 null을 반환한다.
     */
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

    /**
     * 부분 획득 실패나 작업 종료 시 보유 permit을 역순으로 반환한다.
     * 다중 좌석 요청이 남긴 admission 자원을 모두 정리한다.
     */
    private void releaseAll(List<SeatAdmissionPermit> acquired) {
        for (int index = acquired.size() - 1; index >= 0; index--) {
            release(acquired.get(index));
        }
    }

    /**
     * 좌석 permit 한 개를 원래 slot에 반환한다.
     * 모든 permit이 돌아오면 사용하지 않는 slot을 map에서 제거한다.
     */
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

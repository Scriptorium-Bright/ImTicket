package org.example.ticket.reservation.booking.cache;

import java.util.List;

/** 좌석 상태 또는 좌석 배치 변경을 commit 이후 Redis에 반영하기 위한 event다. */
public record SeatMapInvalidationEvent(
        long performanceTimeId,
        List<Long> seatIds
) {

    /** 좌석 배치 생성·변경을 나타내는 전체 재구축 event다.
     * 좌석 ID가 없는 event는 두 읽기 모델을 모두 삭제한다. */
    public SeatMapInvalidationEvent(long performanceTimeId) {
        this(performanceTimeId, List.of());
    }

    /** 전달된 좌석 ID를 중복 제거하고 정렬해 부분 갱신 순서를 안정화한다.
     * null ID는 아직 영속화되지 않은 좌석이므로 상태 갱신 대상에서 제외한다. */
    public SeatMapInvalidationEvent {
        seatIds = seatIds == null
                ? List.of()
                : seatIds.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
    }

    /** 좌석 ID가 없으면 layout과 availability를 함께 재구축해야 하는 구조 변경이다.
     * 좌석 ID가 있으면 해당 availability Hash field만 갱신한다. */
    public boolean requiresFullRebuild() {
        return seatIds.isEmpty();
    }
}

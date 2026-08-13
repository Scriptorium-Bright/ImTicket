package org.example.ticket.reservation.waitingroom.service;

/** 공연 회차별 Waiting Room 적용 여부를 판정하는 정책 contract다. */
@FunctionalInterface
public interface WaitingRoomFeaturePolicy {

    /** 지정한 공연 회차에 Waiting Room 보호 구간을 적용할지 반환한다.
     * 정책 결과는 seat map과 pre-reserve guard가 공유한다. */
    boolean requiresWaitingRoom(long performanceTimeId);
}

package org.example.ticket.reservation.booking.cache;

/** 좌석 layout·availability key 규칙을 한 곳에서 생성한다. */
public final class SeatMapCacheKeyFactory {

    private static final String KEY_PREFIX = "reservation:seat-map:{%d}";

    /** 회차의 정적 layout JSON key를 만든다.
     * 중괄호 해시 태그로 관련 Redis key를 같은 슬롯에 배치한다. */
    public String layout(long performanceTimeId) {
        return KEY_PREFIX.formatted(performanceTimeId) + ":layout:v1";
    }

    /** 정적 layout generation key를 만든다.
     * 구조 변경과 전체 재구축의 경합을 판정할 때 사용한다. */
    public String layoutGeneration(long performanceTimeId) {
        return KEY_PREFIX.formatted(performanceTimeId) + ":layout:generation";
    }

    /** 회차의 동적 availability Hash key를 만든다.
     * 좌석 ID를 Hash field로 저장해 상태 변경 범위를 줄인다. */
    public String availability(long performanceTimeId) {
        return KEY_PREFIX.formatted(performanceTimeId) + ":availability:v1";
    }

    /** 동적 availability generation key를 만든다.
     * 부분 갱신과 전체 재구축의 순서를 비교할 때 사용한다. */
    public String availabilityGeneration(long performanceTimeId) {
        return KEY_PREFIX.formatted(performanceTimeId) + ":availability:generation";
    }

    /** 전체 재구축 중 외부에 노출하지 않는 임시 Hash key를 만든다.
     * 현재 원자 저장 경로의 확장 지점으로 load ID를 key에 포함한다. */
    public String availabilityTemporary(long performanceTimeId, String loadId) {
        return KEY_PREFIX.formatted(performanceTimeId) + ":availability:tmp:" + loadId;
    }

    /** 이전 전체 스냅샷을 정리하기 위한 migration key다. */
    /** 이전 version의 snapshot key를 만든다.
     * 배포 전 잔존한 전체 snapshot을 구조 변경 시 정리하는 데 사용한다. */
    public String legacySnapshot(long performanceTimeId) {
        return KEY_PREFIX.formatted(performanceTimeId) + ":snapshot:v2";
    }

    /** 이전 raw snapshot을 정리하기 위한 migration key다. */
    /** 초기 raw snapshot key를 만든다.
     * migration 기간에 남은 이전 cache 값을 삭제하는 대상이다. */
    public String legacyRawSnapshot(long performanceTimeId) {
        return KEY_PREFIX.formatted(performanceTimeId) + ":snapshot";
    }
}

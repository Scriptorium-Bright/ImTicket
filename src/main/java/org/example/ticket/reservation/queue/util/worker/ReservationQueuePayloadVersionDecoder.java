package org.example.ticket.reservation.queue.util.worker;

import org.example.ticket.reservation.queue.dto.ReservationQueuePayload;

import java.util.Map;

/** 하나의 Queue payload schema를 검증된 값으로 복원하는 decoder 계약이다. */
public interface ReservationQueuePayloadVersionDecoder {

    /**
     * 이 decoder가 처리하는 payload schema version을 반환한다.
     * Registry가 version별 구현을 중복 없이 연결할 때 사용한다.
     */
    int schemaVersion();

    /**
     * Stream field에서 version별 예약 payload를 복원한다.
     * 형식이 잘못된 값은 payload 예외로 변환해 DB 진입을 차단한다.
     */
    ReservationQueuePayload decode(long performanceTimeId, Map<String, String> fields);
}

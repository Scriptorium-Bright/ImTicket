package org.example.ticket.reservation.queue.dto;

import java.util.Map;

/** Consumer Group에서 읽은 Stream ID와 원본 문자열 field를 보존한다. */
public record ReservationQueueStreamMessage(
        long performanceTimeId,
        String streamId,
        Map<String, String> fields
) {

    /**
     * Stream이 속한 회차, entry ID와 원본 field를 검증한다.
     * Decoder가 변경 불가능한 입력만 받도록 field map을 복사한다.
     */
    public ReservationQueueStreamMessage {
        if (performanceTimeId <= 0) {
            throw new IllegalArgumentException("performanceTimeId must be positive");
        }
        if (streamId == null || streamId.isBlank()) {
            throw new IllegalArgumentException("streamId must not be blank");
        }
        if (fields == null) {
            throw new IllegalArgumentException("fields must not be null");
        }
        fields = Map.copyOf(fields);
    }
}

package org.example.ticket.reservation.queue.util.worker;

import org.example.ticket.reservation.common.value.ReservationIdempotencyKey;
import org.example.ticket.reservation.queue.dto.ReservationQueuePayload;
import org.example.ticket.reservation.queue.exception.ReservationQueuePayloadException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Queue payload schema v1의 member, 멱등 키와 좌석 field를 해석한다. */
public final class ReservationQueuePayloadV1Decoder implements ReservationQueuePayloadVersionDecoder {

    /**
     * 현재 구현이 지원하는 schema version 1을 반환한다.
     * Registry가 Stream의 version field와 이 decoder를 연결한다.
     */
    @Override
    public int schemaVersion() {
        return ReservationQueuePayload.CURRENT_SCHEMA_VERSION;
    }

    /**
     * v1 field를 공통 Queue payload로 복원하고 값 불변식을 적용한다.
     * 멱등 키 hash와 정렬 좌석 목록까지 생성 단계와 같은 규칙으로 검증한다.
     */
    @Override
    public ReservationQueuePayload decode(long performanceTimeId, Map<String, String> fields) {
        try {
            return new ReservationQueuePayload(
                    schemaVersion(),
                    Long.parseLong(required(fields, "memberId")),
                    ReservationIdempotencyKey.restore(
                            required(fields, "idempotencyKey"),
                            required(fields, "idempotencyKeyHash")
                    ),
                    required(fields, "requestHash"),
                    seatIds(fields)
            );
        } catch (ReservationQueuePayloadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ReservationQueuePayloadException(
                    ReservationQueuePayloadException.Reason.INVALID_FIELD,
                    "Queue payload v1 field is invalid"
            );
        }
    }

    /**
     * 필수 Stream field를 공백이 없는 문자열로 읽는다.
     * 누락된 값은 안정적인 payload field 오류로 변환한다.
     */
    private String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw new ReservationQueuePayloadException(
                    ReservationQueuePayloadException.Reason.INVALID_FIELD,
                    "Queue payload field is missing: " + name
            );
        }
        return value;
    }

    /**
     * 쉼표로 저장된 좌석 ID를 숫자 목록으로 복원한다.
     * 정렬과 중복 검사는 ReservationQueuePayload 생성자가 수행한다.
     */
    private List<Long> seatIds(Map<String, String> fields) {
        return Arrays.stream(required(fields, "seatIds").split(",", -1))
                .map(Long::parseLong)
                .toList();
    }
}

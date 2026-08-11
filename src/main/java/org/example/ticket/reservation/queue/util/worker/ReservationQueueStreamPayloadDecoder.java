package org.example.ticket.reservation.queue.util.worker;

import org.example.ticket.reservation.common.factory.ReservationIntentFingerprintFactory;
import org.example.ticket.reservation.common.value.ReservationIntentFingerprint;
import org.example.ticket.reservation.queue.dto.ReservationQueuePayload;
import org.example.ticket.reservation.queue.dto.ReservationQueueStreamMessage;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;
import org.example.ticket.reservation.queue.exception.ReservationQueuePayloadException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stream envelope와 versioned payload를 하나의 검증된 Worker 입력으로 변환한다. */
public final class ReservationQueueStreamPayloadDecoder {

    private final Map<Integer, ReservationQueuePayloadVersionDecoder> decoders;

    /**
     * schema version별 decoder를 중복 없이 registry로 구성한다.
     * 빈 registry나 같은 version 구현이 둘 이상이면 시작 단계에서 실패시킨다.
     */
    public ReservationQueueStreamPayloadDecoder(List<ReservationQueuePayloadVersionDecoder> decoders) {
        if (decoders == null || decoders.isEmpty()) {
            throw new IllegalArgumentException("payload decoders must not be empty");
        }
        Map<Integer, ReservationQueuePayloadVersionDecoder> registry = new HashMap<>();
        for (ReservationQueuePayloadVersionDecoder decoder : decoders) {
            if (decoder == null || registry.putIfAbsent(decoder.schemaVersion(), decoder) != null) {
                throw new IllegalArgumentException("payload decoder schemaVersion must be unique");
            }
        }
        this.decoders = Map.copyOf(registry);
    }

    /**
     * Stream envelope를 읽고 version decoder와 공통 fingerprint 검증을 실행한다.
     * 저장된 request hash가 회차와 좌석에서 재계산한 값과 다르면 DB 호출 전에 거절한다.
     */
    public ReservationQueueWorkItem decode(ReservationQueueStreamMessage message) {
        try {
            Map<String, String> fields = message.fields();
            int schemaVersion = Integer.parseInt(required(fields, "payloadSchemaVersion"));
            ReservationQueuePayloadVersionDecoder decoder = decoders.get(schemaVersion);
            if (decoder == null) {
                throw new ReservationQueuePayloadException(
                        ReservationQueuePayloadException.Reason.UNSUPPORTED_SCHEMA,
                        "Unsupported Queue payload schemaVersion: " + schemaVersion
                );
            }

            long storedPerformanceTimeId = Long.parseLong(required(fields, "performanceTimeId"));
            if (storedPerformanceTimeId != message.performanceTimeId()) {
                throw invalid("Stream performanceTimeId does not match its key");
            }
            ReservationQueuePayload payload = decoder.decode(storedPerformanceTimeId, fields);
            verifyFingerprint(storedPerformanceTimeId, payload);
            return new ReservationQueueWorkItem(
                    UUID.fromString(required(fields, "ticketId")),
                    storedPerformanceTimeId,
                    message.streamId(),
                    required(fields, "ownerHash"),
                    UUID.fromString(required(fields, "ownerToken")),
                    payload,
                    Long.parseLong(required(fields, "sequence")),
                    Instant.ofEpochMilli(Long.parseLong(required(fields, "enqueuedAt")))
            );
        } catch (ReservationQueuePayloadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("Queue Stream envelope is invalid");
        }
    }

    /**
     * 회차와 좌석으로 공통 예약 fingerprint를 다시 계산한다.
     * payload가 가진 hash와 불일치하면 손상 또는 위조된 입력으로 분류한다.
     */
    private void verifyFingerprint(long performanceTimeId, ReservationQueuePayload payload) {
        ReservationIntentFingerprint fingerprint = ReservationIntentFingerprintFactory.create(
                performanceTimeId,
                payload.normalizedSeatIds()
        );
        if (!fingerprint.requestHash().equals(payload.requestHash())) {
            throw new ReservationQueuePayloadException(
                    ReservationQueuePayloadException.Reason.FINGERPRINT_MISMATCH,
                    "Queue payload fingerprint does not match performance and seats"
            );
        }
    }

    /**
     * 필수 envelope field를 공백이 없는 문자열로 읽는다.
     * 누락된 값은 decoder 공통 field 오류로 변환한다.
     */
    private String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw invalid("Queue Stream field is missing: " + name);
        }
        return value;
    }

    /**
     * envelope 해석 실패에 사용할 payload 예외를 만든다.
     * 호출 위치와 관계없이 동일한 INVALID_FIELD 분류를 유지한다.
     */
    private ReservationQueuePayloadException invalid(String message) {
        return new ReservationQueuePayloadException(
                ReservationQueuePayloadException.Reason.INVALID_FIELD,
                message
        );
    }
}

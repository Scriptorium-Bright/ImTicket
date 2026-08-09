package org.example.ticket.reservation.booking.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ticket.reservation.booking.exception.ReservationSnapshotException;
import org.example.ticket.reservation.booking.dto.response.ReservationCreateResponse;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReservationResponseSnapshotCodec {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    /**
     * 성공한 예약 생성 응답을 멱등성 replay용 JSON으로 직렬화한다.
     * claim에 저장할 payload 생성 실패는 snapshot 예외로 변환한다.
     */
    public String encode(ReservationCreateResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new ReservationSnapshotException("예약 성공 응답 snapshot을 저장하지 못했습니다.", exception);
        }
    }

    /**
     * 저장된 JSON snapshot을 현재 지원하는 스키마인지 검증한 뒤 예매 생성 응답으로 복원한다.
     * 버전 불일치, 빈 payload와 역직렬화 오류는 명시적인 snapshot 예외로 전달한다.
     */
    public ReservationCreateResponse decode(Integer schemaVersion, String payload) {
        if (schemaVersion == null || schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new ReservationSnapshotException("지원하지 않는 예약 응답 snapshot 버전입니다.");
        }
        if (payload == null || payload.isBlank()) {
            throw new ReservationSnapshotException("예약 성공 응답 snapshot이 비어 있습니다.");
        }
        try {
            return objectMapper.readValue(payload, ReservationCreateResponse.class);
        } catch (JsonProcessingException exception) {
            throw new ReservationSnapshotException("예약 성공 응답 snapshot을 복원하지 못했습니다.", exception);
        }
    }
}

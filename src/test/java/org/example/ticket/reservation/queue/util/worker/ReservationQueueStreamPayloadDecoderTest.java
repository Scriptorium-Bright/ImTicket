package org.example.ticket.reservation.queue.util.worker;

import org.example.ticket.reservation.common.factory.ReservationIntentFingerprintFactory;
import org.example.ticket.reservation.common.value.ReservationIdempotencyKey;
import org.example.ticket.reservation.queue.dto.ReservationQueueStreamMessage;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;
import org.example.ticket.reservation.queue.exception.ReservationQueuePayloadException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationQueueStreamPayloadDecoderTest {

    private final ReservationQueueStreamPayloadDecoder decoder = new ReservationQueueStreamPayloadDecoder(
            List.of(new ReservationQueuePayloadV1Decoder())
    );

    @Test
    void decodesV1PayloadAndRecomputesReservationFingerprint() {
        ReservationQueueStreamMessage message = message(1, requestHash(42L, List.of(1L, 3L)));

        ReservationQueueWorkItem item = decoder.decode(message);

        assertThat(item.performanceTimeId()).isEqualTo(42L);
        assertThat(item.payload().memberId()).isEqualTo(7L);
        assertThat(item.payload().normalizedSeatIds()).containsExactly(1L, 3L);
        assertThat(item.payload().requestHash()).isEqualTo(requestHash(42L, List.of(1L, 3L)));
    }

    @Test
    void rejectsWellFormedHashThatDoesNotMatchPerformanceAndSeats() {
        ReservationQueueStreamMessage message = message(1, "a".repeat(64));

        assertThatThrownBy(() -> decoder.decode(message))
                .isInstanceOfSatisfying(ReservationQueuePayloadException.class, exception ->
                        assertThat(exception.reason())
                                .isEqualTo(ReservationQueuePayloadException.Reason.FINGERPRINT_MISMATCH));
    }

    @Test
    void rejectsUnsupportedSchemaWithExplicitReason() {
        ReservationQueueStreamMessage message = message(2, requestHash(42L, List.of(1L, 3L)));

        assertThatThrownBy(() -> decoder.decode(message))
                .isInstanceOfSatisfying(ReservationQueuePayloadException.class, exception ->
                        assertThat(exception.reason())
                                .isEqualTo(ReservationQueuePayloadException.Reason.UNSUPPORTED_SCHEMA));
    }

    private ReservationQueueStreamMessage message(int schemaVersion, String requestHash) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ticketId", "f76f5ac8-a475-4e04-906a-1f54765f9770");
        fields.put("performanceTimeId", "42");
        fields.put("ownerHash", "b".repeat(64));
        fields.put("ownerToken", "da64524f-ac82-45a8-9d38-4cd641b72343");
        fields.put("payloadSchemaVersion", String.valueOf(schemaVersion));
        fields.put("memberId", "7");
        fields.put("idempotencyKey", "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1");
        fields.put("idempotencyKeyHash", ReservationIdempotencyKey.from(
                "a0ebc4c9-8d82-47af-8127-1fc3d27e47a1").hash());
        fields.put("requestHash", requestHash);
        fields.put("seatIds", "1,3");
        fields.put("sequence", "9");
        fields.put("enqueuedAt", "1786356000000");
        return new ReservationQueueStreamMessage(42L, "1786356000000-0", fields);
    }

    private String requestHash(long performanceTimeId, List<Long> seatIds) {
        return ReservationIntentFingerprintFactory.create(performanceTimeId, seatIds).requestHash();
    }
}

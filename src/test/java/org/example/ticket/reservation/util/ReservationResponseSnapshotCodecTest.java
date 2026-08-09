package org.example.ticket.reservation.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ticket.reservation.exception.ReservationSnapshotException;
import org.example.ticket.reservation.response.ReservationCreateResponse;
import org.example.ticket.reservation.response.SeatResponse;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationResponseSnapshotCodecTest {

    private final ReservationResponseSnapshotCodec codec = new ReservationResponseSnapshotCodec(
            new ObjectMapper().findAndRegisterModules()
    );

    @Test
    void roundTripPreservesCreateResponse() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 7, 18, 23, 0);
        ReservationCreateResponse response = ReservationCreateResponse.builder()
                .id(10L)
                .totalPrice(45000)
                .orderUid("reservation-10")
                .expiredTime(expiresAt)
                .responses(List.of(SeatResponse.builder()
                        .id(20L)
                        .seatFloor(1)
                        .seatSection("A")
                        .seatRow(2)
                        .seatNumber(3)
                        .seatType(SeatInfo.VIP)
                        .price(45000)
                        .isReservation(true)
                        .seatStatus(SeatStatus.LOCKED)
                        .build()))
                .build();

        ReservationCreateResponse replay = codec.decode(
                ReservationResponseSnapshotCodec.CURRENT_SCHEMA_VERSION,
                codec.encode(response)
        );

        assertThat(replay.getId()).isEqualTo(10L);
        assertThat(replay.getOrderUid()).isEqualTo("reservation-10");
        assertThat(replay.getExpiredTime()).isEqualTo(expiresAt);
        assertThat(replay.getResponses()).singleElement().satisfies(seat -> {
            assertThat(seat.getId()).isEqualTo(20L);
            assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.LOCKED);
            assertThat(seat.getPrice()).isEqualTo(45000);
        });
    }

    @Test
    void rejectsUnknownSchemaVersionAndCorruptPayload() {
        assertThatThrownBy(() -> codec.decode(2, "{}"))
                .isInstanceOf(ReservationSnapshotException.class);
        assertThatThrownBy(() -> codec.decode(1, "not-json"))
                .isInstanceOf(ReservationSnapshotException.class);
    }
}

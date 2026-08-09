package org.example.ticket.reservation.booking.util;

import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.exception.ReservationSnapshotException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationFailureSnapshotCodecTest {

    private final ReservationFailureSnapshotCodec codec = new ReservationFailureSnapshotCodec();

    @Test
    void roundTripsPublicErrorCodeWithCurrentSchema() {
        String code = codec.encode(ReservationErrorCode.SEAT_ALREADY_RESERVED);

        assertThat(code).isEqualTo("SEAT_ALREADY_RESERVED");
        assertThat(codec.decode(ReservationFailureSnapshotCodec.CURRENT_SCHEMA_VERSION, code))
                .isEqualTo(ReservationErrorCode.SEAT_ALREADY_RESERVED);
    }

    @Test
    void rejectsUnknownSchemaAndCode() {
        assertThatThrownBy(() -> codec.decode(99, "SEAT_ALREADY_RESERVED"))
                .isInstanceOf(ReservationSnapshotException.class);
        assertThatThrownBy(() -> codec.decode(
                ReservationFailureSnapshotCodec.CURRENT_SCHEMA_VERSION,
                "UNKNOWN_ERROR"
        )).isInstanceOf(ReservationSnapshotException.class);
    }
}


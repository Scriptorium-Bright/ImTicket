package org.example.ticket.reservation.shared.intent;

import org.example.ticket.reservation.booking.api.ReservationRequest;
import org.example.ticket.reservation.booking.support.ReservationRequestHasher;
import org.example.ticket.reservation.queue.domain.ReservationQueueRequestFingerprint;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationIntentFingerprintContractTest {

    private static final String SCHEMA_VERSION = "reservation-pre-reserve:v1";

    @Test
    void bookingAndQueueProduceTheSameFingerprint() {
        var booking = new ReservationRequestHasher().fingerprint(
                new ReservationRequest(10L, List.of(3L, 1L))
        );
        var queue = ReservationQueueRequestFingerprint.of(10L, List.of(1L, 3L));

        assertThat(booking.requestHash()).isEqualTo(queue.requestHash());
        assertThat(booking.normalizedSeatIds()).containsExactly(1L, 3L);
        assertThat(queue.normalizedSeatIds()).containsExactly(1L, 3L);
    }

    @Test
    void commonFingerprintCarriesTheCanonicalIntentAsAnImmutableValue() throws Exception {
        Class<?> factoryType = Class.forName(
                "org.example.ticket.reservation.shared.intent.ReservationIntentFingerprintFactory"
        );
        Method create = factoryType.getMethod("create", long.class, List.class);
        Object fingerprint = create.invoke(null, 10L, List.of(3L, 1L));

        assertThat(read(fingerprint, "schemaVersion")).isEqualTo(SCHEMA_VERSION);
        assertThat(read(fingerprint, "performanceTimeId")).isEqualTo(10L);
        assertThat(read(fingerprint, "normalizedSeatIds")).isEqualTo(List.of(1L, 3L));
        assertThat(read(fingerprint, "requestHash").toString()).matches("[0-9a-f]{64}");

        @SuppressWarnings("unchecked")
        List<Long> normalizedSeatIds = (List<Long>) read(fingerprint, "normalizedSeatIds");
        assertThatThrownBy(() -> normalizedSeatIds.add(4L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void bookingAndQueueDelegateCanonicalizationToTheSharedFactory() throws Exception {
        String bookingSource = Files.readString(Path.of(
                "src/main/java/org/example/ticket/reservation/booking/support/ReservationRequestHasher.java"
        ));
        String queueSource = Files.readString(Path.of(
                "src/main/java/org/example/ticket/reservation/queue/domain/ReservationQueueRequestFingerprint.java"
        ));

        assertThat(bookingSource).contains("ReservationIntentFingerprintFactory.create");
        assertThat(queueSource).contains("ReservationIntentFingerprintFactory.create");
        assertThat(bookingSource).doesNotContain("MessageDigest", SCHEMA_VERSION);
        assertThat(queueSource).doesNotContain("MessageDigest", SCHEMA_VERSION);
    }

    private Object read(Object target, String accessor) throws Exception {
        return target.getClass().getMethod(accessor).invoke(target);
    }
}

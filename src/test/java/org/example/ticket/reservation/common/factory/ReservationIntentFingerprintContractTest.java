package org.example.ticket.reservation.common.factory;

import org.example.ticket.reservation.booking.dto.request.ReservationRequest;
import org.example.ticket.reservation.booking.util.ReservationRequestHasher;
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
        var queue = ReservationIntentFingerprintFactory.create(10L, List.of(1L, 3L));

        assertThat(booking.requestHash()).isEqualTo(queue.requestHash());
        assertThat(booking.normalizedSeatIds()).containsExactly(1L, 3L);
        assertThat(queue.normalizedSeatIds()).containsExactly(1L, 3L);
    }

    @Test
    void commonFingerprintCarriesTheCanonicalIntentAsAnImmutableValue() throws Exception {
        Class<?> factoryType = Class.forName(
                "org.example.ticket.reservation.common.factory.ReservationIntentFingerprintFactory"
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
                "src/main/java/org/example/ticket/reservation/booking/util/ReservationRequestHasher.java"
        ));
        String factorySource = Files.readString(Path.of(
                "src/main/java/org/example/ticket/reservation/common/factory/ReservationIntentFingerprintFactory.java"
        ));

        assertThat(bookingSource).contains("ReservationIntentFingerprintFactory.create");
        assertThat(factorySource).contains("public static ReservationIntentFingerprint create");
        assertThat(bookingSource).doesNotContain("MessageDigest", SCHEMA_VERSION);
        assertThat(factorySource).contains("MessageDigest", SCHEMA_VERSION);
    }

    private Object read(Object target, String accessor) throws Exception {
        return target.getClass().getMethod(accessor).invoke(target);
    }
}

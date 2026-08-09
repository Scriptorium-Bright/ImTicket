package org.example.ticket.reservation.booking.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationBookingPackageBoundaryTest {

    private static final Path RESERVATION_SOURCE = Path.of(
            "src/main/java/org/example/ticket/reservation"
    );
    private static final String BOOKING_PACKAGE = "org.example.ticket.reservation.booking";

    private static final List<String> EXPECTED_TYPES = List.of(
            BOOKING_PACKAGE + ".api.ReservationController",
            BOOKING_PACKAGE + ".api.SeatController",
            BOOKING_PACKAGE + ".api.ReservationRequest",
            BOOKING_PACKAGE + ".api.ReservationCreateResponse",
            BOOKING_PACKAGE + ".api.SeatResponse",
            BOOKING_PACKAGE + ".application.ReservationCompletionService",
            BOOKING_PACKAGE + ".application.ReservationExpirationService",
            BOOKING_PACKAGE + ".application.ReservationIdempotencyTransactionService",
            BOOKING_PACKAGE + ".application.ReservationIdempotentCreationService",
            BOOKING_PACKAGE + ".application.ReservationPreReserveService",
            BOOKING_PACKAGE + ".application.ReservationService",
            BOOKING_PACKAGE + ".application.SeatService",
            BOOKING_PACKAGE + ".application.ReservationClaimSnapshot",
            BOOKING_PACKAGE + ".application.ReservationExpirationResult",
            BOOKING_PACKAGE + ".application.ReservationRequestFingerprint",
            BOOKING_PACKAGE + ".application.SeatCreationData",
            BOOKING_PACKAGE + ".domain.Reservation",
            BOOKING_PACKAGE + ".domain.ReservationIdempotency",
            BOOKING_PACKAGE + ".domain.ReservationIdempotencyStatus",
            BOOKING_PACKAGE + ".domain.ReservationErrorCode",
            BOOKING_PACKAGE + ".domain.ReservedSeat",
            BOOKING_PACKAGE + ".domain.Seat",
            BOOKING_PACKAGE + ".persistence.ReservationIdempotencyRepository",
            BOOKING_PACKAGE + ".persistence.ReservationRepository",
            BOOKING_PACKAGE + ".persistence.SeatRepository",
            BOOKING_PACKAGE + ".concurrency.SeatAdmission",
            BOOKING_PACKAGE + ".concurrency.SeatAdmissionPermit",
            BOOKING_PACKAGE + ".concurrency.SeatAdmissionService",
            BOOKING_PACKAGE + ".concurrency.SeatAdmissionSlot",
            BOOKING_PACKAGE + ".concurrency.ReservationLock",
            BOOKING_PACKAGE + ".concurrency.ReservationLockAspect",
            BOOKING_PACKAGE + ".concurrency.ReservationLockOperation",
            BOOKING_PACKAGE + ".concurrency.ReservationLockStrategy",
            BOOKING_PACKAGE + ".concurrency.ReservationLockStrategyContext",
            BOOKING_PACKAGE + ".support.ReservationSnapshotException",
            BOOKING_PACKAGE + ".support.ReservationRequestHasher",
            BOOKING_PACKAGE + ".support.ReservationResponseSnapshotCodec",
            BOOKING_PACKAGE + ".support.ReservationValidator"
    );

    private static final List<String> LEGACY_PACKAGES = List.of(
            "admission",
            "controller",
            "dto",
            "exception",
            "lock",
            "model",
            "repository",
            "request",
            "response",
            "service",
            "util",
            "validation"
    );

    @Test
    void bookingTypesAreGroupedByResponsibility() {
        List<String> missingTypes = EXPECTED_TYPES.stream()
                .filter(type -> !canLoad(type))
                .toList();

        assertThat(missingTypes).isEmpty();
    }

    @Test
    void productionTypesDoNotRemainInLegacyReservationPackages() throws IOException {
        for (String legacyPackage : LEGACY_PACKAGES) {
            Path directory = RESERVATION_SOURCE.resolve(legacyPackage);
            if (!Files.exists(directory)) {
                continue;
            }
            try (var files = Files.walk(directory)) {
                assertThat(files.filter(this::isJavaSource).toList())
                        .as("legacy package %s", legacyPackage)
                        .isEmpty();
            }
        }
    }

    @Test
    void queueAndBookingApplicationsDoNotDependOnEachOthersInfrastructure() throws IOException {
        assertSourcesDoNotContain(
                RESERVATION_SOURCE.resolve("booking/application"),
                "org.example.ticket.reservation.queue.infrastructure"
        );
        assertSourcesDoNotContain(
                RESERVATION_SOURCE.resolve("queue/application"),
                "org.example.ticket.reservation.booking.persistence",
                "org.example.ticket.reservation.booking.concurrency"
        );
    }

    private void assertSourcesDoNotContain(Path directory, String... forbiddenImports) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            List<Path> violations = files
                    .filter(this::isJavaSource)
                    .filter(path -> containsAny(path, forbiddenImports))
                    .toList();
            assertThat(violations).isEmpty();
        }
    }

    private boolean containsAny(Path path, String... values) {
        try {
            String source = Files.readString(path);
            return List.of(values).stream().anyMatch(source::contains);
        } catch (IOException exception) {
            throw new IllegalStateException("Java source를 읽을 수 없습니다: " + path, exception);
        }
    }

    private boolean isJavaSource(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
    }

    private boolean canLoad(String type) {
        try {
            Class.forName(type, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}

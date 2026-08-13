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
            BOOKING_PACKAGE + ".controller.ReservationController",
            BOOKING_PACKAGE + ".controller.SeatController",
            BOOKING_PACKAGE + ".dto.request.ReservationRequest",
            BOOKING_PACKAGE + ".dto.response.ReservationCreateResponse",
            BOOKING_PACKAGE + ".dto.response.SeatResponse",
            BOOKING_PACKAGE + ".service.ReservationCompletionService",
            BOOKING_PACKAGE + ".service.ReservationExpirationService",
            BOOKING_PACKAGE + ".service.ReservationIdempotencyTransactionService",
            BOOKING_PACKAGE + ".service.ReservationIdempotentCreationService",
            BOOKING_PACKAGE + ".service.ReservationClaimExecutionService",
            BOOKING_PACKAGE + ".service.ReservationPreReserveService",
            BOOKING_PACKAGE + ".service.ReservationService",
            BOOKING_PACKAGE + ".service.SeatService",
            BOOKING_PACKAGE + ".dto.ReservationClaimSnapshot",
            BOOKING_PACKAGE + ".dto.ReservationExpirationResult",
            BOOKING_PACKAGE + ".dto.SeatCreationData",
            BOOKING_PACKAGE + ".domain.Reservation",
            BOOKING_PACKAGE + ".domain.ReservationIdempotency",
            BOOKING_PACKAGE + ".domain.ReservationIdempotencyStatus",
            BOOKING_PACKAGE + ".domain.ReservedSeat",
            BOOKING_PACKAGE + ".domain.Seat",
            BOOKING_PACKAGE + ".repository.ReservationIdempotencyRepository",
            BOOKING_PACKAGE + ".repository.ReservationRepository",
            BOOKING_PACKAGE + ".repository.SeatRepository",
            BOOKING_PACKAGE + ".constant.ReservationErrorCode",
            BOOKING_PACKAGE + ".constant.ReservationFailureType",
            BOOKING_PACKAGE + ".exception.ReservationSnapshotException",
            BOOKING_PACKAGE + ".util.admission.SeatAdmission",
            BOOKING_PACKAGE + ".util.admission.SeatAdmissionPermit",
            BOOKING_PACKAGE + ".util.admission.SeatAdmissionService",
            BOOKING_PACKAGE + ".util.admission.SeatAdmissionSlot",
            BOOKING_PACKAGE + ".util.annotation.ReservationLock",
            BOOKING_PACKAGE + ".util.aop.ReservationLockAspect",
            BOOKING_PACKAGE + ".util.lock.ReservationLockOperation",
            BOOKING_PACKAGE + ".util.lock.ReservationLockStrategy",
            BOOKING_PACKAGE + ".util.lock.ReservationLockStrategyContext",
            BOOKING_PACKAGE + ".util.ReservationRequestHasher",
            BOOKING_PACKAGE + ".util.ReservationFailureClassifier",
            BOOKING_PACKAGE + ".util.ReservationFailureSnapshotCodec",
            BOOKING_PACKAGE + ".util.ReservationResponseSnapshotCodec",
            BOOKING_PACKAGE + ".util.ReservationValidator",
            BOOKING_PACKAGE + ".util.idempotency.ReservationIdempotencyKey",
            BOOKING_PACKAGE + ".util.idempotency.ReservationIntentFingerprint",
            BOOKING_PACKAGE + ".util.idempotency.ReservationIntentFingerprintFactory"
    );

    private static final List<String> LEGACY_PACKAGES = List.of(
            "booking/api",
            "booking/application",
            "booking/concurrency",
            "booking/persistence",
            "booking/support",
            "shared"
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

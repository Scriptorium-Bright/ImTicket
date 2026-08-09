package org.example.ticket.reservation.queue.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationQueuePackageBoundaryTest {

    private static final String QUEUE_PACKAGE = "org.example.ticket.reservation.queue";

    private static final List<String> EXPECTED_TYPES = List.of(
            QUEUE_PACKAGE + ".api.ReservationQueueController",
            QUEUE_PACKAGE + ".application.ReservationQueueService",
            QUEUE_PACKAGE + ".application.ReservationQueueExpiryService",
            QUEUE_PACKAGE + ".application.ReservationQueueProperties",
            QUEUE_PACKAGE + ".application.ReservationQueueIdentityHasher",
            QUEUE_PACKAGE + ".application.ReservationQueueAdmissionCommand",
            QUEUE_PACKAGE + ".application.ReservationQueueAdmissionResult",
            QUEUE_PACKAGE + ".application.ReservationQueueApiRequest",
            QUEUE_PACKAGE + ".application.ReservationQueueEnqueueResponse",
            QUEUE_PACKAGE + ".application.ReservationQueueStatusResponse",
            QUEUE_PACKAGE + ".application.ReservationQueueErrorCode",
            QUEUE_PACKAGE + ".application.ReservationQueueTicketSnapshot",
            QUEUE_PACKAGE + ".application.port.ReservationQueueAdmissionStore",
            QUEUE_PACKAGE + ".application.port.ReservationQueueTicketStore",
            QUEUE_PACKAGE + ".application.port.ReservationQueueExpiryIndex",
            QUEUE_PACKAGE + ".application.port.ReservationQueueStorageException",
            QUEUE_PACKAGE + ".domain.ReservationQueueStatus",
            QUEUE_PACKAGE + ".domain.ReservationQueueRequestFingerprint",
            QUEUE_PACKAGE + ".infrastructure.redis.RedisReservationQueueAdmissionStore",
            QUEUE_PACKAGE + ".infrastructure.redis.ReservationQueueAdmissionRedisCommands",
            QUEUE_PACKAGE + ".infrastructure.redis.RedisReservationQueueTicketStore",
            QUEUE_PACKAGE + ".infrastructure.redis.RedisReservationQueueExpiryIndex",
            QUEUE_PACKAGE + ".infrastructure.redis.ReservationQueueKeyFactory",
            QUEUE_PACKAGE + ".infrastructure.scheduling.ReservationQueueExpiryScheduler",
            QUEUE_PACKAGE + ".config.ReservationQueueConfiguration"
    );

    @Test
    void queueTypesAreGroupedByResponsibility() {
        List<String> missingTypes = EXPECTED_TYPES.stream()
                .filter(type -> !canLoad(type))
                .toList();

        assertThat(missingTypes).isEmpty();
    }

    @Test
    void productionTypesDoNotRemainInFlatQueuePackage() throws IOException {
        Path flatPackage = Path.of(
                "src/main/java/org/example/ticket/reservation/queue"
        );

        try (var files = Files.list(flatPackage)) {
            assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
        }
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

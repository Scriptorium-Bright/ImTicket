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
            QUEUE_PACKAGE + ".controller.ReservationQueueController",
            QUEUE_PACKAGE + ".service.ReservationQueueService",
            QUEUE_PACKAGE + ".service.ReservationQueueExpiryService",
            QUEUE_PACKAGE + ".service.ReservationQueueProcessor",
            QUEUE_PACKAGE + ".service.ReservationQueueMaintenanceService",
            QUEUE_PACKAGE + ".config.ReservationQueueProperties",
            QUEUE_PACKAGE + ".config.ReservationQueueWorkerProperties",
            QUEUE_PACKAGE + ".config.ReservationQueueRetryProperties",
            QUEUE_PACKAGE + ".config.ReservationQueueMaintenanceProperties",
            QUEUE_PACKAGE + ".util.ReservationQueueIdentityHasher",
            QUEUE_PACKAGE + ".dto.ReservationQueueAdmissionCommand",
            QUEUE_PACKAGE + ".dto.ReservationQueueAdmissionResult",
            QUEUE_PACKAGE + ".dto.request.ReservationQueueApiRequest",
            QUEUE_PACKAGE + ".dto.response.ReservationQueueEnqueueResponse",
            QUEUE_PACKAGE + ".dto.response.ReservationQueueStatusResponse",
            QUEUE_PACKAGE + ".constant.ReservationQueueErrorCode",
            QUEUE_PACKAGE + ".dto.ReservationQueueTicketSnapshot",
            QUEUE_PACKAGE + ".repository.ReservationQueueAdmissionStore",
            QUEUE_PACKAGE + ".repository.ReservationQueueTicketStore",
            QUEUE_PACKAGE + ".repository.ReservationQueueExpiryIndex",
            QUEUE_PACKAGE + ".repository.ReservationQueueWorkerStore",
            QUEUE_PACKAGE + ".repository.ReservationQueueTerminalStore",
            QUEUE_PACKAGE + ".repository.ReservationQueueRetryStore",
            QUEUE_PACKAGE + ".repository.ReservationQueueMaintenanceStore",
            QUEUE_PACKAGE + ".exception.ReservationQueueStorageException",
            QUEUE_PACKAGE + ".constant.ReservationQueueStatus",
            QUEUE_PACKAGE + ".repository.redis.RedisReservationQueueAdmissionStore",
            QUEUE_PACKAGE + ".repository.redis.ReservationQueueAdmissionRedisCommands",
            QUEUE_PACKAGE + ".repository.redis.RedisReservationQueueTicketStore",
            QUEUE_PACKAGE + ".repository.redis.RedisReservationQueueExpiryIndex",
            QUEUE_PACKAGE + ".repository.redis.RedisReservationQueueWorkerStore",
            QUEUE_PACKAGE + ".repository.redis.RedisReservationQueueTerminalStore",
            QUEUE_PACKAGE + ".repository.redis.RedisReservationQueueRetryStore",
            QUEUE_PACKAGE + ".repository.redis.RedisReservationQueueMaintenanceStore",
            QUEUE_PACKAGE + ".repository.redis.ReservationQueueKeyFactory",
            QUEUE_PACKAGE + ".util.scheduler.ReservationQueueExpiryScheduler",
            QUEUE_PACKAGE + ".config.ReservationQueueConfiguration",
            QUEUE_PACKAGE + ".service.ReservationQueueWorkHandler",
            QUEUE_PACKAGE + ".dto.ReservationQueueStreamMessage",
            QUEUE_PACKAGE + ".dto.ReservationQueueWorkItem",
            QUEUE_PACKAGE + ".dto.ReservationQueueClaimResult",
            QUEUE_PACKAGE + ".dto.ReservationQueueSuccessResult",
            QUEUE_PACKAGE + ".dto.ReservationQueueTerminalResult",
            QUEUE_PACKAGE + ".dto.ReservationQueueRetryResult",
            QUEUE_PACKAGE + ".dto.ReservationQueueMaintenanceResult",
            QUEUE_PACKAGE + ".dto.ReservationQueueMappingRepairResult",
            QUEUE_PACKAGE + ".exception.ReservationQueuePayloadException",
            QUEUE_PACKAGE + ".util.worker.ReservationQueuePayloadVersionDecoder",
            QUEUE_PACKAGE + ".util.worker.ReservationQueuePayloadV1Decoder",
            QUEUE_PACKAGE + ".util.worker.ReservationQueueStreamPayloadDecoder",
            QUEUE_PACKAGE + ".util.worker.ReservationQueueWorkerPermits",
            QUEUE_PACKAGE + ".util.worker.ReservationQueueWorkerPoller",
            QUEUE_PACKAGE + ".util.scheduler.ReservationQueueMaintenanceScheduler"
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

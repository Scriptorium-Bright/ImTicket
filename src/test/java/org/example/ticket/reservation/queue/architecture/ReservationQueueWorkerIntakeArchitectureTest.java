package org.example.ticket.reservation.queue.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationQueueWorkerIntakeArchitectureTest {

    private static final List<Path> INTAKE_PATHS = List.of(
            Path.of("src/main/java/org/example/ticket/reservation/queue/util/worker"),
            Path.of("src/main/java/org/example/ticket/reservation/queue/repository/redis/RedisReservationQueueWorkerStore.java"),
            Path.of("src/main/java/org/example/ticket/reservation/queue/repository/redis/ReservationQueueWorkerRedisCommands.java")
    );

    @Test
    void workerIntakeDoesNotDependOnBookingOrDatabaseConnections() throws IOException {
        for (Path path : INTAKE_PATHS) {
            if (Files.isDirectory(path)) {
                try (var files = Files.walk(path)) {
                    assertThat(files.filter(Files::isRegularFile).map(this::source).toList())
                            .allSatisfy(this::hasNoDatabaseDependency);
                }
            } else {
                hasNoDatabaseDependency(source(path));
            }
        }
    }

    private String source(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Worker source를 읽을 수 없습니다: " + path, exception);
        }
    }

    private void hasNoDatabaseDependency(String source) {
        assertThat(source).doesNotContain(
                "org.example.ticket.reservation.booking",
                "javax.sql",
                "jakarta.persistence",
                "DataSource",
                "EntityManager"
        );
    }
}

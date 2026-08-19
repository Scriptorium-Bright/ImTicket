package org.example.ticket.reservation.waitingroom.sse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitingRoomSseEmitterRegistryTest {

    /** ticket별 연결 상한과 close 뒤 registry 정리를 검증한다. */
    @Test
    void enforcesConnectionLimitAndReleasesClosedConnection() {
        WaitingRoomProperties properties = new WaitingRoomProperties();
        properties.setSseMaxConnectionsPerTicket(1);
        WaitingRoomSseEmitterRegistry registry = new WaitingRoomSseEmitterRegistry(
                properties,
                new SyncTaskExecutor(),
                new SimpleMeterRegistry()
        );
        UUID ticketId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

        WaitingRoomSseConnection connection = registry.register(7L, ticketId, 12L);

        assertThat(registry.connectionCount()).isEqualTo(1);
        assertThatThrownBy(() -> registry.register(7L, ticketId, 12L))
                .isInstanceOf(WaitingRoomSseConnectionLimitException.class);

        registry.close(connection, "test");

        assertThat(registry.connectionCount()).isZero();
    }
}

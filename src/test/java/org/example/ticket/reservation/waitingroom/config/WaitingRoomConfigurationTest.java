package org.example.ticket.reservation.waitingroom.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitingRoomConfigurationTest {

    @Test
    void rejectsPlaceholderPassSecretWhenWaitingRoomIsEnabled() {
        WaitingRoomProperties properties = new WaitingRoomProperties();
        properties.setEnabled(true);
        properties.setEnabledPerformanceTimeIds(Set.of(7L));

        assertThatThrownBy(() -> new WaitingRoomConfiguration().waitingRoomPassCodec(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pass-secret");
    }
}

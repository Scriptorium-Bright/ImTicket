package org.example.ticket.reservation.waitingroom.repository.redis;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitingRoomKeyFactoryTest {

    private final WaitingRoomKeyFactory factory = new WaitingRoomKeyFactory();
    private final UUID ticketId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** 회차별 Waiting Room key가 문서화한 prefix와 hash tag를 유지하는지 검증한다. */
    @Test
    void createsKeysWithSharedPerformanceHashTag() {
        assertThat(factory.sequence(7)).isEqualTo("reservation:waiting-room:{7}:sequence");
        assertThat(factory.waiting(7)).isEqualTo("reservation:waiting-room:{7}:waiting");
        assertThat(factory.active(7)).isEqualTo("reservation:waiting-room:{7}:active");
        assertThat(factory.deadline(7)).isEqualTo("reservation:waiting-room:{7}:deadline");
        assertThat(factory.ticket(7, ticketId))
                .isEqualTo("reservation:waiting-room:{7}:ticket:11111111-1111-1111-1111-111111111111");
        assertThat(factory.owner(7, 12)).isEqualTo("reservation:waiting-room:{7}:owner:12");
    }

    /** 잘못된 회차, 회원, ticket 입력을 key 생성 전에 거절하는지 검증한다. */
    @Test
    void rejectsInvalidIdentifiers() {
        assertThatThrownBy(() -> factory.sequence(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.owner(7, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.ticket(7, null)).isInstanceOf(IllegalArgumentException.class);
    }
}

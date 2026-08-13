package org.example.ticket.reservation.waitingroom.pass;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitingRoomPassClaimsTest {

    private static final UUID TICKET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant ISSUED_AT = Instant.parse("2026-08-14T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-14T00:05:00Z");

    /** pass claim이 회원·회차·ticket을 함께 연결하는지 검증한다. */
    @Test
    void matchesOwnerAndPerformance() {
        WaitingRoomPassClaims claims = claims();

        assertThat(claims.belongsTo(12, 7)).isTrue();
        assertThat(claims.belongsTo(13, 7)).isFalse();
        assertThat(claims.belongsTo(12, 8)).isFalse();
        assertThat(claims.isExpired(ISSUED_AT)).isFalse();
        assertThat(claims.isExpired(EXPIRES_AT)).isTrue();
    }

    /** pass claim의 식별자와 시간 불변식이 잘못되면 생성되지 않는지 검증한다. */
    @Test
    void rejectsInvalidClaimValues() {
        assertThatThrownBy(() -> new WaitingRoomPassClaims(
                TICKET_ID, 0, 7, ISSUED_AT, EXPIRES_AT
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WaitingRoomPassClaims(
                TICKET_ID, 12, 7, EXPIRES_AT, ISSUED_AT
        )).isInstanceOf(IllegalArgumentException.class);
    }

    /** 테스트에서 사용할 유효한 pass claim을 생성한다. */
    private WaitingRoomPassClaims claims() {
        return new WaitingRoomPassClaims(TICKET_ID, 12, 7, ISSUED_AT, EXPIRES_AT);
    }
}

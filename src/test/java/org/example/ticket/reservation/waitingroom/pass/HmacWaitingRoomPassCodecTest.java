package org.example.ticket.reservation.waitingroom.pass;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacWaitingRoomPassCodecTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant ISSUED_AT = Instant.parse("2026-08-14T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-14T00:05:00Z");

    /** HMAC pass를 발급한 뒤 같은 secret으로 원래 claim을 복원하는지 검증한다. */
    @Test
    void issuesAndParsesSignedPass() {
        HmacWaitingRoomPassCodec codec = new HmacWaitingRoomPassCodec("test-secret");
        WaitingRoomPassClaims claims = claims();

        String token = codec.issue(claims);

        assertThat(codec.parse(token)).isEqualTo(claims);
        assertThat(token).contains(".");
    }

    /** payload 또는 서명이 바뀐 pass를 검증 단계에서 거절하는지 검증한다. */
    @Test
    void rejectsTamperedPassAndDifferentSecret() {
        HmacWaitingRoomPassCodec codec = new HmacWaitingRoomPassCodec("test-secret");
        String token = codec.issue(claims());
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> codec.parse(tampered))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HmacWaitingRoomPassCodec("other-secret").parse(token))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 테스트에서 사용할 서명 가능한 pass claim을 생성한다. */
    private WaitingRoomPassClaims claims() {
        return new WaitingRoomPassClaims(TICKET_ID, 12L, 7L, ISSUED_AT, EXPIRES_AT);
    }
}

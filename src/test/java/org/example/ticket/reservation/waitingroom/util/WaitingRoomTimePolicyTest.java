package org.example.ticket.reservation.waitingroom.util;

import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitingRoomTimePolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private WaitingRoomProperties properties;

    @BeforeEach
    void setUp() {
        properties = new WaitingRoomProperties();
        properties.setWaitingTicketTtl(Duration.ofMinutes(30));
        properties.setEntryLease(Duration.ofMinutes(5));
        properties.setTerminalRetention(Duration.ofHours(1));
        properties.setStatusPollAfter(Duration.ofSeconds(2));
    }

    /** 고정 clock에서 waiting deadline과 admitted lease의 계산 기준을 검증한다. */
    @Test
    void calculatesDeadlinesFromInjectedClock() {
        WaitingRoomTimePolicy policy = policy();

        assertThat(policy.now()).isEqualTo(NOW);
        assertThat(policy.waitingDeadline()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(policy.entryLeaseExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(policy.terminalRetentionExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(policy.statusPollAfter()).isEqualTo(Duration.ofSeconds(2));
    }

    /** 만료 판정과 Redis score 변환이 동일한 clock contract를 사용하는지 검증한다. */
    @Test
    void detectsExpiredTargetsAndConvertsEpochMillis() {
        WaitingRoomTimePolicy policy = policy();
        Instant target = NOW.plusSeconds(1);

        assertThat(policy.isExpired(NOW.minusMillis(1))).isTrue();
        assertThat(policy.isExpired(NOW)).isTrue();
        assertThat(policy.isExpired(target)).isFalse();
        assertThat(policy.epochMillis(target)).isEqualTo(target.toEpochMilli());
    }

    /** 잘못된 시간 설정이 policy 생성 단계에서 실패하는지 검증한다. */
    @Test
    void rejectsInvalidDurationConfiguration() {
        properties.setEntryLease(Duration.ZERO);

        assertThatThrownBy(this::policy)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entryLease");
    }

    /** 테스트에서 사용할 고정 시각 policy를 구성한다. */
    private WaitingRoomTimePolicy policy() {
        return new WaitingRoomTimePolicy(Clock.fixed(NOW, ZoneOffset.UTC), properties);
    }
}

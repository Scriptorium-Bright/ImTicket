package org.example.ticket.reservation.waitingroom.util;

import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Waiting Room ticket deadline과 admitted lease의 시간 계산을 담당한다. */
public final class WaitingRoomTimePolicy {

    private final Clock clock;
    private final WaitingRoomProperties properties;

    /** 운영 clock과 Waiting Room 설정을 받아 결정적인 시간 정책을 구성한다.
     * 테스트는 고정 clock을 주입해 deadline과 lease를 재현할 수 있다. */
    public WaitingRoomTimePolicy(Clock clock, WaitingRoomProperties properties) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /** 현재 정책 기준 시각을 반환한다.
     * 모든 Waiting Room 시간 계산은 같은 clock을 사용한다. */
    public Instant now() {
        return clock.instant();
    }

    /** 새 WAITING ticket이 만료될 시각을 계산한다.
     * ticket 생성 시각에 waiting ticket TTL을 더해 계산한다. */
    public Instant waitingDeadline() {
        return now().plus(properties.getWaitingTicketTtl());
    }

    /** ADMITTED ticket의 entry lease 만료 시각을 계산한다.
     * promotion 시각에 active session lease를 더해 계산한다. */
    public Instant entryLeaseExpiresAt() {
        return now().plus(properties.getEntryLease());
    }

    /** terminal ticket의 Redis 보존 종료 시각을 계산한다.
     * 운영 조회와 장애 진단에 필요한 보존 기간을 함께 반영한다. */
    public Instant terminalRetentionExpiresAt() {
        return now().plus(properties.getTerminalRetention());
    }

    /** 대상 시각이 현재 clock 기준으로 지났는지 확인한다.
     * 현재 시각과 같은 deadline도 만료 상태로 처리한다. */
    public boolean isExpired(Instant target) {
        Objects.requireNonNull(target, "target must not be null");
        return !target.isAfter(now());
    }

    /** Redis Sorted Set score에 사용할 epoch millisecond를 반환한다.
     * active lease 만료 대상의 범위 조회에 사용되는 숫자다. */
    public long epochMillis(Instant target) {
        Objects.requireNonNull(target, "target must not be null");
        return target.toEpochMilli();
    }

    /** 설정된 status polling 간격을 반환한다.
     * API가 클라이언트에게 다음 조회 시점을 안내할 때 사용한다. */
    public Duration statusPollAfter() {
        return properties.getStatusPollAfter();
    }

    /** 대기 순번 구간에 맞는 다음 polling 간격을 반환한다.
     * 먼 순번의 polling 빈도를 낮춰 대규모 status 요청이 만드는 Redis·Tomcat 부하를 분산한다. */
    public Duration statusPollAfter(Long position) {
        if (position == null || position <= properties.getStatusPollMiddleThreshold()) {
            return properties.getStatusPollAfter();
        }
        if (position <= properties.getStatusPollFarThreshold()) {
            return properties.getStatusPollMiddleAfter();
        }
        return properties.getStatusPollFarAfter();
    }
}

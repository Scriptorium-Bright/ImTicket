package org.example.ticket.reservation.waitingroom.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/** Waiting Room의 admission, lease, 보존 시간을 외부 설정으로 관리한다. */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "reservation.waiting-room")
public class WaitingRoomProperties {

    /** 개발용 placeholder pass secret이다. Waiting Room 활성화 시 운영 설정으로 교체해야 한다. */
    public static final String DEFAULT_PASS_SECRET = "change-me-waiting-room-pass-secret";

    private boolean enabled;
    @Positive
    private int maxActiveSessions = 100;
    @Positive
    private int maxWaitingTickets = 50_000;
    @Positive
    private int admitPerInterval = 10;
    @NotNull
    private Duration waitingTicketTtl = Duration.ofMinutes(30);
    @NotNull
    private Duration entryLease = Duration.ofMinutes(5);
    @NotNull
    private Duration terminalRetention = Duration.ofHours(1);
    @NotNull
    private Duration promotionInterval = Duration.ofSeconds(1);
    @NotNull
    private Duration statusPollAfter = Duration.ofSeconds(2);
    @PositiveOrZero
    private int statusPollMiddleThreshold = 100;
    @Positive
    private int statusPollFarThreshold = 1_000;
    @NotNull
    private Duration statusPollMiddleAfter = Duration.ofSeconds(5);
    @NotNull
    private Duration statusPollFarAfter = Duration.ofSeconds(10);
    @NotBlank
    private String passSecret = DEFAULT_PASS_SECRET;
    @NotNull
    private Set<Long> enabledPerformanceTimeIds = new HashSet<>();
}

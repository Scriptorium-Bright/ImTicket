package org.example.ticket.reservation.waitingroom.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Waiting Room의 admission, lease, 보존 시간을 외부 설정으로 관리한다. */
@Getter
@Setter
@ConfigurationProperties(prefix = "reservation.waiting-room")
public class WaitingRoomProperties {

    private boolean enabled;
    private int maxActiveSessions = 100;
    private int admitPerInterval = 10;
    private Duration waitingTicketTtl = Duration.ofMinutes(30);
    private Duration entryLease = Duration.ofMinutes(5);
    private Duration terminalRetention = Duration.ofHours(1);
    private Duration promotionInterval = Duration.ofSeconds(1);
    private Duration statusPollAfter = Duration.ofSeconds(2);

    /** 설정값이 Waiting Room의 admission과 시간 계산에 사용할 수 있는지 검증한다.
     * 잘못된 값은 애플리케이션 시작 또는 policy 구성 단계에서 거절한다. */
    public void validate() {
        if (maxActiveSessions <= 0) {
            throw new IllegalArgumentException("maxActiveSessions must be positive");
        }
        if (admitPerInterval <= 0) {
            throw new IllegalArgumentException("admitPerInterval must be positive");
        }
        requirePositive(waitingTicketTtl, "waitingTicketTtl");
        requirePositive(entryLease, "entryLease");
        requirePositive(terminalRetention, "terminalRetention");
        requirePositive(promotionInterval, "promotionInterval");
        requirePositive(statusPollAfter, "statusPollAfter");
    }

    /** 지정한 duration이 양수인지 확인해 시간 정책에 잘못된 값을 전달하지 않게 한다.
     * null, zero, 음수 duration을 모두 설정 오류로 처리한다. */
    private void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

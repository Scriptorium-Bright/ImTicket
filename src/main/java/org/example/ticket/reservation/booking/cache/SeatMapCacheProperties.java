package org.example.ticket.reservation.booking.cache;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/** 좌석 배치도 snapshot cache의 외부 설정을 보관한다. */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "reservation.seat-map-cache")
public class SeatMapCacheProperties {

    private boolean enabled;

    /** 명시적으로 cache를 적용할 공연 회차 목록이다. 비어 있으면 cache를 적용하지 않는다. */
    @NotNull
    private Set<Long> enabledPerformanceTimeIds = new HashSet<>();

    /** cache miss 뒤 저장되는 snapshot의 보조 TTL이다. */
    @NotNull
    private Duration ttl = Duration.ofMinutes(5);
}

package org.example.ticket.reservation.booking.cache;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
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

    /** 한 공연 회차에서 동시에 DB fallback으로 진입할 수 있는 최대 요청 수다. */
    @Min(1)
    private int fallbackMaxConcurrency = 5;

    /** 한 공연 회차에서 동시에 Redis snapshot을 읽을 수 있는 최대 요청 수다. */
    @Min(1)
    private int cacheReadMaxConcurrency = 200;

    /** 동일 회차의 동시 cache miss를 하나의 재구축 작업으로 합칠지 결정한다. */
    private boolean singleFlightEnabled = true;

    /** joiner가 owner 결과를 기다릴 수 있는 최대 시간이다. */
    @NotNull
    private Duration singleFlightWaitTimeout = Duration.ofSeconds(2);
}

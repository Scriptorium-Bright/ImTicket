package org.example.ticket.reservation.booking.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 좌석 cache 설정과 Redis storage 구현을 애플리케이션 bean으로 연결한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SeatMapCacheProperties.class)
public class SeatMapCacheConfiguration {

    /**
     * cache feature flag를 회차 목록과 결합하는 정책 bean을 등록한다.
     * cache 적용 범위의 판단 책임을 configuration properties 밖에 둔다.
     */
    @Bean
    public SeatMapCacheFeaturePolicy seatMapCacheFeaturePolicy(SeatMapCacheProperties properties) {
        return new SeatMapCacheFeaturePolicy(properties);
    }

    /**
     * 좌석 snapshot Redis key 규칙을 공유하는 factory bean을 등록한다.
     * reader와 invalidation listener가 동일한 key를 사용하게 한다.
     */
    @Bean
    public SeatMapCacheKeyFactory seatMapCacheKeyFactory() {
        return new SeatMapCacheKeyFactory();
    }

    /**
     * JSON Redis 구현을 cache store contract에 연결한다.
     * cache application service가 Redis 세부 구현에 의존하지 않게 한다.
     */
    @Bean
    public SeatMapCacheStore seatMapCacheStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            SeatMapCacheKeyFactory keyFactory
    ) {
        return new RedisSeatMapCacheStore(redisTemplate, objectMapper, keyFactory);
    }
}

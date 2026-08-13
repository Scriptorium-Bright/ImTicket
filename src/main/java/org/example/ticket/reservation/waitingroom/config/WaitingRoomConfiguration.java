package org.example.ticket.reservation.waitingroom.config;

import org.example.ticket.reservation.waitingroom.pass.HmacWaitingRoomPassCodec;
import org.example.ticket.reservation.waitingroom.pass.WaitingRoomPassCodec;
import org.example.ticket.reservation.waitingroom.repository.redis.WaitingRoomKeyFactory;
import org.example.ticket.reservation.waitingroom.service.DefaultWaitingRoomFeaturePolicy;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomFeaturePolicy;
import org.example.ticket.reservation.waitingroom.util.WaitingRoomTimePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Waiting Room contract와 Redis·pass API lifecycle의 Spring bean을 연결한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WaitingRoomProperties.class)
public class WaitingRoomConfiguration {

    /** 애플리케이션에 사용할 UTC clock을 등록한다.
     * Waiting Room의 deadline과 entry lease 계산이 같은 시간 기준을 사용하게 한다. */
    @Bean
    public Clock waitingRoomClock() {
        return Clock.systemUTC();
    }

    /** Waiting Room의 회차별 Redis key factory를 등록한다.
     * Redis repository와 scheduler가 같은 key 규칙을 공유하게 한다. */
    @Bean
    public WaitingRoomKeyFactory waitingRoomKeyFactory() {
        return new WaitingRoomKeyFactory();
    }

    /** 설정과 clock을 결합한 Waiting Room time policy를 등록한다.
     * 운영 시간과 테스트 시간의 주입 경계를 bean으로 고정한다. */
    @Bean
    public WaitingRoomTimePolicy waitingRoomTimePolicy(
            Clock waitingRoomClock,
            WaitingRoomProperties properties
    ) {
        return new WaitingRoomTimePolicy(waitingRoomClock, properties);
    }

    /** 전역 flag와 선택 회차 목록을 적용하는 feature policy를 등록한다.
     * seat map과 reservation guard가 동일한 적용 결과를 사용하게 한다. */
    @Bean
    public WaitingRoomFeaturePolicy waitingRoomFeaturePolicy(WaitingRoomProperties properties) {
        return new DefaultWaitingRoomFeaturePolicy(properties);
    }

    /** entry pass 서명과 검증을 담당하는 HMAC codec을 등록한다.
     * pass secret은 WaitingRoomProperties에서 외부 설정으로 주입한다. */
    @Bean
    public WaitingRoomPassCodec waitingRoomPassCodec(WaitingRoomProperties properties) {
        return new HmacWaitingRoomPassCodec(properties.getPassSecret());
    }
}

package org.example.ticket.reservation.booking.cache;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SeatMapCacheFeaturePolicyTest {

    @Test
    void onlyExplicitlyEnabledPerformanceTimeUsesCache() {
        SeatMapCacheProperties properties = new SeatMapCacheProperties();
        properties.setEnabled(true);
        properties.setEnabledPerformanceTimeIds(Set.of(7L));
        SeatMapCacheFeaturePolicy policy = new SeatMapCacheFeaturePolicy(properties);

        assertThat(policy.appliesTo(7L)).isTrue();
        assertThat(policy.appliesTo(8L)).isFalse();
    }

    @Test
    void emptyTargetListKeepsCacheDisabledForSafety() {
        SeatMapCacheProperties properties = new SeatMapCacheProperties();
        properties.setEnabled(true);
        SeatMapCacheFeaturePolicy policy = new SeatMapCacheFeaturePolicy(properties);

        assertThat(policy.appliesTo(7L)).isFalse();
    }
}

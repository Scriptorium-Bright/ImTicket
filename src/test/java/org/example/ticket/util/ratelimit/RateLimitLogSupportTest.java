package org.example.ticket.util.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitLogSupportTest {

    @Test
    void redactKeyReturnsStableShortHash() {
        String first = RateLimitLogSupport.redactKey("0xabc:15:1,2,3");
        String second = RateLimitLogSupport.redactKey("0xabc:15:1,2,3");

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(12);
    }

    @Test
    void redactKeyReturnsUnknownForBlankInput() {
        assertThat(RateLimitLogSupport.redactKey("   ")).isEqualTo("unknown");
    }
}

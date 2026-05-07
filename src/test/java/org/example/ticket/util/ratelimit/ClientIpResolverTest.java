package org.example.ticket.util.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    void resolvesFirstForwardedIpWhenHeaderContainsMultipleValues() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "198.51.100.10, 10.0.0.1");
        request.setRemoteAddr("127.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.10");
    }

    @Test
    void fallsBackToRemoteAddressWhenForwardedHeadersMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void normalizesWalletAndPhoneKeys() {
        assertThat(resolver.normalizeWallet(" 0xAbCd ")).isEqualTo("0xabcd");
        assertThat(resolver.normalizePhone("010-1234-5678")).isEqualTo("01012345678");
    }

    @Test
    void rejectsBlankWalletAndPhone() {
        assertThrows(IllegalArgumentException.class, () -> resolver.normalizeWallet(" "));
        assertThrows(IllegalArgumentException.class, () -> resolver.normalizePhone(" "));
    }
}

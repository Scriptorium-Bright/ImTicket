package org.example.ticket.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ticket.security.LoginRequestDto;
import org.example.ticket.security.handler.LoginFailureHandler;
import org.example.ticket.security.handler.LoginSuccessHandler;
import org.example.ticket.security.token.MetamaskAuthenticationToken;
import org.example.ticket.util.ratelimit.BusinessRateLimitGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetamaskAuthenticationFilterTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private LoginSuccessHandler loginSuccessHandler;

    @Mock
    private LoginFailureHandler loginFailureHandler;

    @Mock
    private BusinessRateLimitGuard businessRateLimitGuard;

    @Mock
    private Authentication authentication;

    @Test
    void attemptAuthenticationChecksWalletIpRateLimitBeforeAuthenticationManager() throws Exception {
        MetamaskAuthenticationFilter filter = new MetamaskAuthenticationFilter(
                authenticationManager,
                objectMapper,
                loginSuccessHandler,
                loginFailureHandler,
                businessRateLimitGuard
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/signature/verify");
        MockHttpServletResponse response = new MockHttpServletResponse();
        LoginRequestDto loginRequestDto = new LoginRequestDto();
        loginRequestDto.setWalletAddress("0xABCD");
        loginRequestDto.setSignature("signature");

        request.setRemoteAddr("198.51.100.11");
        request.setContentType("application/json");
        request.setContent("{\"walletAddress\":\"0xABCD\",\"signature\":\"signature\"}".getBytes());

        when(objectMapper.readValue(any(java.io.InputStream.class), eq(LoginRequestDto.class))).thenReturn(loginRequestDto);
        when(businessRateLimitGuard.resolveClientIp(request)).thenReturn("198.51.100.11");
        when(authenticationManager.authenticate(any(MetamaskAuthenticationToken.class))).thenReturn(authentication);

        Authentication result = filter.attemptAuthentication(request, response);

        verify(businessRateLimitGuard).checkSignatureVerify("0xABCD", "198.51.100.11");
        verify(authenticationManager).authenticate(any(MetamaskAuthenticationToken.class));
        assertThat(result).isSameAs(authentication);
    }
}

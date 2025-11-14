package org.example.ticket.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper; // JSON 직렬화를 위해
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.security.LoginRequestDto;
import org.example.ticket.security.handler.LoginFailureHandler;
import org.example.ticket.security.handler.LoginSuccessHandler;
import org.example.ticket.security.provider.JwtTokenProvider;
import org.example.ticket.security.token.MetamaskAuthenticationToken;
import org.example.ticket.security.util.MetamaskUserDetails;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

@Slf4j
public class MetamaskAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

    private static final String SPRING_WEB_LOGIN_URI = "/api/user/signature/verify";
    private static final String HTTP_METHOD_TYPE = "POST";

    private final ObjectMapper objectMapper; // JSON 응답 작성을 위해 주입
    // 생성자에서 의존성 주입
    public MetamaskAuthenticationFilter(AuthenticationManager authenticationManager,
                                        JwtTokenProvider jwtTokenProvider,
                                        ObjectMapper objectMapper, LoginSuccessHandler loginSuccessHandler, LoginFailureHandler loginFailureHandler) {
        super(new AntPathRequestMatcher(SPRING_WEB_LOGIN_URI, HTTP_METHOD_TYPE), authenticationManager);
        setAuthenticationSuccessHandler(loginSuccessHandler);
        setAuthenticationFailureHandler(loginFailureHandler);
        this.objectMapper = objectMapper;
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException, IOException { // IOException 추가

        LoginRequestDto loginRequestDto;
        try {
            loginRequestDto = objectMapper.readValue(request.getInputStream(), LoginRequestDto.class);
        } catch (IOException e) {
            log.error("Failed to parse authentication request body", e);
            throw new BadCredentialsException("Invalid request body format");
        }

        MetamaskAuthenticationToken authRequest = getMetamaskAuthenticationToken(loginRequestDto);
        authRequest.setDetails(this.authenticationDetailsSource.buildDetails(request));

        return this.getAuthenticationManager().authenticate(authRequest);
    }

    @NotNull
    private static MetamaskAuthenticationToken getMetamaskAuthenticationToken(LoginRequestDto loginRequestDto) {
        String walletAddress = loginRequestDto.getWalletAddress();
        String signature = loginRequestDto.getSignature();

        if (walletAddress == null || walletAddress.isEmpty()) {
            throw new BadCredentialsException("Wallet address is NULL or empty");
        }
        if (signature == null || signature.isEmpty()) {
            throw new BadCredentialsException("Signature is NULL or empty");
        }

        return new MetamaskAuthenticationToken(walletAddress, signature);
    }

}
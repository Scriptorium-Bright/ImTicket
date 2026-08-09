package org.example.ticket.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper; // JSON 직렬화를 위해
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.security.request.LoginRequest;
import org.example.ticket.security.handler.LoginFailureHandler;
import org.example.ticket.security.handler.LoginSuccessHandler;
import org.example.ticket.security.token.MetamaskAuthenticationToken;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.io.IOException;

@Slf4j
public class MetamaskAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

    private static final String SPRING_WEB_LOGIN_URI = "/api/user/signature/verify";
    private static final String HTTP_METHOD_TYPE = "POST";

    private final ObjectMapper objectMapper; // JSON 응답 작성을 위해 주입

    public MetamaskAuthenticationFilter(AuthenticationManager authenticationManager,
            ObjectMapper objectMapper, LoginSuccessHandler loginSuccessHandler,
            LoginFailureHandler loginFailureHandler) {
        super(new AntPathRequestMatcher(SPRING_WEB_LOGIN_URI, HTTP_METHOD_TYPE), authenticationManager);
        setAuthenticationSuccessHandler(loginSuccessHandler);
        setAuthenticationFailureHandler(loginFailureHandler);
        this.objectMapper = objectMapper;
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException, IOException { // IOException 추가

        LoginRequest loginRequest;
        try {
            loginRequest = objectMapper.readValue(request.getInputStream(), LoginRequest.class);
        } catch (IOException e) {
            log.error("Failed to parse authentication request body", e);
            throw new BadCredentialsException("Invalid request body format");
        }

        MetamaskAuthenticationToken authRequest = getMetamaskAuthenticationToken(loginRequest);
        authRequest.setDetails(this.authenticationDetailsSource.buildDetails(request));

        return this.getAuthenticationManager().authenticate(authRequest);
    }

    @NotNull
    private static MetamaskAuthenticationToken getMetamaskAuthenticationToken(LoginRequest loginRequest) {
        String walletAddress = loginRequest.walletAddress();
        String signature = loginRequest.signature();

        if (walletAddress == null || walletAddress.isEmpty()) {
            throw new BadCredentialsException("Wallet address is NULL or empty");
        }
        if (signature == null || signature.isEmpty()) {
            throw new BadCredentialsException("Signature is NULL or empty");
        }

        return new MetamaskAuthenticationToken(walletAddress, signature);
    }

}

package org.example.ticket.security.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.security.dto.TokenResponse;
import org.example.ticket.security.jwt.JwtUtil;
import org.example.ticket.security.util.MetamaskUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;

@Slf4j
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public LoginSuccessHandler(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {

        MetamaskUserDetails metamaskUserDetails = (MetamaskUserDetails) authentication.getPrincipal();
        String walletAddress = metamaskUserDetails.getAddress();

        String role = authentication.getAuthorities().stream().findFirst().map(GrantedAuthority::getAuthority)
                .orElse("");

        log.info("MetamaskAuthenticationFilter: Authentication successful for {}. Issuing JWT.", walletAddress);

        String token = jwtUtil.createJwt(walletAddress, role);

        TokenResponse tokenResponse = TokenResponse.builder()
                .token(token)
                .walletAddress(walletAddress)
                .role(role)
                .build();

        log.info("wallet address = {}", walletAddress);
        log.info("role = {}", role);

        setResponseStatus(response, tokenResponse);
    }

    private void setResponseStatus(HttpServletResponse response, TokenResponse tokenResponse) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(tokenResponse));
        response.getWriter().flush();
    }
}

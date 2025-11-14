package org.example.ticket.security.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.security.provider.JwtTokenProvider;
import org.example.ticket.security.util.MetamaskUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

@Slf4j
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;

    public LoginSuccessHandler(JwtTokenProvider jwtTokenProvider, ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
    }


    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {

        MetamaskUserDetails metamaskUserDetails = (MetamaskUserDetails) authentication.getPrincipal();
        String walletAddress = metamaskUserDetails.getAddress();

        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        Iterator<? extends GrantedAuthority> iterator = authorities.iterator();

        String role = authentication.getAuthorities().stream().findFirst().map(GrantedAuthority::getAuthority).orElse("");


        log.info("MetamaskAuthenticationFilter: Authentication successful for {}. Issuing JWT.", walletAddress);

        Map<String, String> jwtMap = jwtTokenProvider.provideJwt(walletAddress, role);

        log.info("wallet address = {}", walletAddress);
        log.info("role = {}", role);

        setResponseStatus(response, jwtMap);
    }

    private void setResponseStatus(HttpServletResponse response, Map<String, String> jwtMap) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(jwtMap));
        response.getWriter().flush();
    }
}

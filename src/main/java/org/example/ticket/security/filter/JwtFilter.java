package org.example.ticket.security.filter;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.model.Member;
import org.example.ticket.security.jwt.JwtUtil;
import org.example.ticket.security.util.MetamaskUserDetails;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
@Slf4j
public class JwtFilter extends OncePerRequestFilter {

    private final static String AUTHORIZATION_HEADER = "Authorization";
    private final static String AUTHORIZATION_HEADER_CERTIFIED = "Bearer ";
    private final JwtUtil jwtUtil;

    public JwtFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        String authorization = request.getHeader(AUTHORIZATION_HEADER);

        log.info("authorization = {}", authorization);

        if(!certifiedHeader(authorization, request, response, filterChain)) return;

        String token = authorization.split(" ")[1];
        Claims claims = jwtUtil.parseClaims(token);

        if (jwtUtil.isExpired(claims)) {
            filterChain.doFilter(request, response);
            return;
        }

        String walletAddress = jwtUtil.getUsername(claims);
        String role = jwtUtil.getRole(claims);

        log.info("wallet address : {}, role = {}", walletAddress, role);

        Member member = Member.builder()
                .walletAddress(walletAddress)
                .role(role)
                .build();

        MetamaskUserDetails userDetails = new MetamaskUserDetails(member);

        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                userDetails,
                null,
                Collections.singletonList(new SimpleGrantedAuthority(role))
        );

        SecurityContextHolder.getContext().setAuthentication(authToken);

        log.info("jwt success");
        filterChain.doFilter(request, response);
    }

    public boolean certifiedHeader(String authorization, HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (authorization == null || !authorization.startsWith(AUTHORIZATION_HEADER_CERTIFIED)) {
            filterChain.doFilter(request, response);
            return false;
        }
        return true;
    }

}

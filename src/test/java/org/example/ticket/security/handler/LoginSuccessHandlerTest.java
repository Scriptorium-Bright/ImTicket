package org.example.ticket.security.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ticket.member.model.Member;
import org.example.ticket.security.jwt.JwtUtil;
import org.example.ticket.security.principal.MetamaskUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoginSuccessHandlerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void issuesTokenWithAuthenticatedMemberId() throws Exception {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000L);
        ObjectMapper objectMapper = new ObjectMapper();
        LoginSuccessHandler handler = new LoginSuccessHandler(jwtUtil, objectMapper);
        MetamaskUserDetails principal = new MetamaskUserDetails(Member.builder()
                .id(42L)
                .walletAddress("0xowner")
                .role("ROLE_USER")
                .build());
        var authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        String token = body.get("token").asText();
        assertThat(jwtUtil.getMemberId(jwtUtil.parseClaims(token))).isEqualTo(42L);
        assertThat(body.get("walletAddress").asText()).isEqualTo("0xowner");
    }
}

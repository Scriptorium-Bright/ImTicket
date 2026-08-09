package org.example.ticket.security.filter;

import io.jsonwebtoken.Jwts;
import org.example.ticket.security.jwt.JwtUtil;
import org.example.ticket.security.principal.MetamaskUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JwtFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000L);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void buildsPrincipalFromSignedMemberIdentity() throws Exception {
        filter(jwtUtil.createJwt(42L, "0xowner", "ROLE_USER"));

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isInstanceOf(MetamaskUserDetails.class);
        MetamaskUserDetails principal = (MetamaskUserDetails) authentication.getPrincipal();
        assertThat(principal.getMemberId()).isEqualTo(42L);
        assertThat(principal.getAddress()).isEqualTo("0xowner");
    }

    @Test
    void leavesRequestUnauthenticatedWhenSignedTokenHasNoMemberId() throws Exception {
        SecretKey key = new SecretKeySpec(
                SECRET.getBytes(StandardCharsets.UTF_8),
                Jwts.SIG.HS256.key().build().getAlgorithm()
        );
        String token = Jwts.builder()
                .claim(JwtUtil.CLAIM_WALLET_ADDRESS, "0xowner")
                .claim(JwtUtil.CLAIM_ROLE, "ROLE_USER")
                .signWith(key)
                .compact();

        filter(token);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void leavesRequestUnauthenticatedForTokenSignedByAnotherKey() throws Exception {
        JwtUtil forgedIssuer = new JwtUtil("abcdef0123456789abcdef0123456789", 60_000L);

        filter(forgedIssuer.createJwt(999L, "0xowner", "ROLE_USER"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void filter(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        new JwtFilter(jwtUtil).doFilter(
                request,
                new MockHttpServletResponse(),
                new MockFilterChain()
        );
    }
}

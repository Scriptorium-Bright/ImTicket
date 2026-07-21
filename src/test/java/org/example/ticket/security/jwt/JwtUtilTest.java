package org.example.ticket.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void parsesTheSameSignedTokenConcurrently() throws Exception {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000L);
        String token = jwtUtil.createJwt("0xLoadTestUser", "ROLE_USER");
        List<Callable<Claims>> tasks = java.util.stream.IntStream.range(0, 32)
                .<Callable<Claims>>mapToObj(ignored -> () -> jwtUtil.parseClaims(token))
                .toList();

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Claims>> results = executor.invokeAll(tasks);

            for (Future<Claims> result : results) {
                Claims claims = result.get();
                assertThat(jwtUtil.getUsername(claims)).isEqualTo("0xLoadTestUser");
                assertThat(jwtUtil.getRole(claims)).isEqualTo("ROLE_USER");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsAnInvalidSignedToken() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000L);

        assertThatThrownBy(() -> jwtUtil.parseClaims("invalid.token.value"))
                .isInstanceOf(JwtException.class);
    }
}

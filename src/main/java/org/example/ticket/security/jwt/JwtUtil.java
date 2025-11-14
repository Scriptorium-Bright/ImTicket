package org.example.ticket.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Component
public class JwtUtil {

    private final SecretKey secretKey;

    public JwtUtil(@Value("${spring.jwt.secret}") String secret) {
        log.info("jwt Util process");
        if (secret == null) {
            throw new IllegalArgumentException("JWT secret key is null");
        }
        this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), Jwts.SIG.HS256.key().build().getAlgorithm());
    }

    public Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
    }

    public String getUsername(Claims claim) {

        return claim.get("walletAddress", String.class);
    }

    public String getRole(Claims claim) {

        return claim.get("role", String.class);
    }

    public Boolean isExpired(Claims claim) {

        return claim.getExpiration().before(new Date());
    }


    public String createJwt(String walletAddress, String role, Long expiredMs) {

        log.info("create JWT process");

        if (secretKey == null) {
            throw new IllegalStateException("Secret key is not initialized");
        }
        return Jwts.builder()
                .claim("walletAddress", walletAddress)
                .claim("role", role)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiredMs))
                .signWith(secretKey)
                .compact();
    }
}

package org.example.ticket.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
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

    public static final String CLAIM_WALLET_ADDRESS = "walletAddress";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_MEMBER_ID = "memberId";

    private final SecretKey secretKey;
    private final JwtParser jwtParser;
    private final Long expiredMs;

    public JwtUtil(@Value("${spring.jwt.secret}") String secret, @Value("${spring.jwt.expired.time}") Long expiredMs) {
        if (secret == null) {
            throw new IllegalArgumentException("JWT secret key is null");
        }
        this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),
                Jwts.SIG.HS256.key().build().getAlgorithm());
        this.jwtParser = Jwts.parser().verifyWith(secretKey).build();
        this.expiredMs = expiredMs;
    }

    public Claims parseClaims(String token) {
        return jwtParser.parseSignedClaims(token).getPayload();
    }

    public String getUsername(Claims claim) {
        return claim.get(CLAIM_WALLET_ADDRESS, String.class);
    }

    public String getRole(Claims claim) {
        return claim.get(CLAIM_ROLE, String.class);
    }

    public long getMemberId(Claims claim) {
        Object value = claim.get(CLAIM_MEMBER_ID);
        if (!(value instanceof Number)) {
            throw new MalformedJwtException("JWT memberId claim must be a number");
        }
        try {
            long memberId = Long.parseLong(value.toString());
            if (memberId <= 0) {
                throw new NumberFormatException("memberId must be positive");
            }
            return memberId;
        } catch (NumberFormatException exception) {
            throw new MalformedJwtException("JWT memberId claim must be a positive integer", exception);
        }
    }

    public String createJwt(long memberId, String walletAddress, String role) {

        if (secretKey == null) {
            throw new IllegalStateException("Secret key is not initialized");
        }
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        if (walletAddress == null || walletAddress.isBlank()) {
            throw new IllegalArgumentException("walletAddress must not be blank");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        return Jwts.builder()
                .claim(CLAIM_MEMBER_ID, memberId)
                .claim(CLAIM_WALLET_ADDRESS, walletAddress)
                .claim(CLAIM_ROLE, role)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiredMs))
                .signWith(secretKey)
                .compact();
    }
}

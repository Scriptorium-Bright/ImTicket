package org.example.ticket.util.ratelimit;

import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.example.ticket.util.tracing.TracingConstants.CORRELATION_ID_MDC_KEY;

public final class RateLimitLogSupport {

    private RateLimitLogSupport() {
    }

    public static String correlationId() {
        return MDC.get(CORRELATION_ID_MDC_KEY);
    }

    public static String redactKey(String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isBlank()) {
            return "unknown";
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(normalizedKey.hashCode());
        }
    }
}

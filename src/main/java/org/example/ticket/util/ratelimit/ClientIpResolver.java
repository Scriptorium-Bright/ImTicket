package org.example.ticket.util.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ClientIpResolver {

    private static final List<String> FORWARDED_HEADERS = List.of(
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP"
    );

    public String resolve(HttpServletRequest request) {
        for (String header : FORWARDED_HEADERS) {
            String value = request.getHeader(header);
            if (value == null || value.isBlank() || "unknown".equalsIgnoreCase(value)) {
                continue;
            }

            String firstIp = value.split(",")[0].trim();
            if (!firstIp.isBlank() && !"unknown".equalsIgnoreCase(firstIp)) {
                return firstIp;
            }
        }

        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr;
    }

    public String normalizeIp(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return "unknown";
        }

        return rawIp.trim();
    }

    public String normalizeWallet(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            throw new IllegalArgumentException("walletAddress must not be blank");
        }

        return walletAddress.trim().toLowerCase();
    }

    public String normalizePhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("phoneNumber must not be blank");
        }

        return phoneNumber.replaceAll("[^0-9]", "");
    }
}

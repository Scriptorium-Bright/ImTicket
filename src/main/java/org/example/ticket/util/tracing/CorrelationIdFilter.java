package org.example.ticket.util.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Pattern ALLOWED_CORRELATION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        String correlationId = resolveCorrelationId(request.getHeader(TracingConstants.CORRELATION_ID_HEADER));

        request.setAttribute(TracingConstants.CORRELATION_ID_REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(TracingConstants.CORRELATION_ID_HEADER, correlationId);
        MDC.put(TracingConstants.CORRELATION_ID_MDC_KEY, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            restoreMdc(previousContext);
        }
    }

    private String resolveCorrelationId(String inboundCorrelationId) {
        String invalidReason = getInvalidReason(inboundCorrelationId);

        if (invalidReason == null) {
            return inboundCorrelationId;
        }

        if (!"missing".equals(invalidReason)) {
            log.warn("Rejected inbound correlation id header. reason={}, length={}",
                    invalidReason,
                    inboundCorrelationId.length());
        }

        return UUID.randomUUID().toString();
    }

    private String getInvalidReason(String inboundCorrelationId) {
        if (inboundCorrelationId == null) {
            return "missing";
        }

        if (inboundCorrelationId.isBlank()) {
            return "blank";
        }

        if (inboundCorrelationId.length() > TracingConstants.MAX_CORRELATION_ID_LENGTH) {
            return "too_long";
        }

        if (!ALLOWED_CORRELATION_ID_PATTERN.matcher(inboundCorrelationId).matches()) {
            return "invalid_pattern";
        }

        return null;
    }

    private void restoreMdc(Map<String, String> previousContext) {
        if (previousContext == null || previousContext.isEmpty()) {
            MDC.clear();
            return;
        }

        MDC.setContextMap(previousContext);
    }
}

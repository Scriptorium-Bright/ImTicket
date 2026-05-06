package org.example.ticket.util.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

public class RequestTracingFilter extends OncePerRequestFilter {

    private static final Pattern ALLOWED_CORRELATION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request.getHeader(TracingConstants.CORRELATION_ID_HEADER));

        request.setAttribute(TracingConstants.CORRELATION_ID_REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(TracingConstants.CORRELATION_ID_HEADER, correlationId);
        MDC.put(TracingConstants.CORRELATION_ID_MDC_KEY, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TracingConstants.CORRELATION_ID_MDC_KEY);
        }
    }

    private String resolveCorrelationId(String inboundCorrelationId) {
        if (isValidInboundCorrelationId(inboundCorrelationId)) {
            return inboundCorrelationId;
        }

        return UUID.randomUUID().toString();
    }

    private boolean isValidInboundCorrelationId(String inboundCorrelationId) {
        if (inboundCorrelationId == null
                || inboundCorrelationId.isBlank()
                || inboundCorrelationId.length() > TracingConstants.MAX_CORRELATION_ID_LENGTH) {
            return false;
        }

        return ALLOWED_CORRELATION_ID_PATTERN.matcher(inboundCorrelationId).matches();
    }
}

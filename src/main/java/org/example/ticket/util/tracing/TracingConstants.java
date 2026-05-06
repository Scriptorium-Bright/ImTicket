package org.example.ticket.util.tracing;

public final class TracingConstants {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    public static final String RUN_ID_MDC_KEY = "runId";
    public static final String CORRELATION_ID_REQUEST_ATTRIBUTE = "org.example.ticket.correlationId";
    public static final int MAX_CORRELATION_ID_LENGTH = 128;

    private TracingConstants() {
    }
}

package org.example.ticket.util.tracing;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void reusesValidInboundCorrelationIdAndClearsMdcAfterRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TracingConstants.CORRELATION_ID_HEADER, "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> requestAttribute = new AtomicReference<>();
        AtomicReference<String> mdcValueInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            requestAttribute.set((String) request.getAttribute(TracingConstants.CORRELATION_ID_REQUEST_ATTRIBUTE));
            mdcValueInsideChain.set(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY));
        });

        assertThat(requestAttribute.get()).isEqualTo("trace-123");
        assertThat(mdcValueInsideChain.get()).isEqualTo("trace-123");
        assertThat(response.getHeader(TracingConstants.CORRELATION_ID_HEADER)).isEqualTo("trace-123");
        assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)).isNull();
    }

    @Test
    void generatesUuidWhenInboundCorrelationIdIsInvalidAndLogsWarning(CapturedOutput output)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TracingConstants.CORRELATION_ID_HEADER, "invalid value with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> requestAttribute = new AtomicReference<>();
        AtomicReference<String> mdcValueInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            requestAttribute.set((String) request.getAttribute(TracingConstants.CORRELATION_ID_REQUEST_ATTRIBUTE));
            mdcValueInsideChain.set(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY));
        });

        assertThat(UUID.fromString(requestAttribute.get())).isNotNull();
        assertThat(mdcValueInsideChain.get()).isEqualTo(requestAttribute.get());
        assertThat(response.getHeader(TracingConstants.CORRELATION_ID_HEADER)).isEqualTo(requestAttribute.get());
        assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)).isNull();
        assertThat(output.getOut()).contains("Rejected inbound correlation id header. reason=invalid_pattern, length=25");
    }

    @Test
    void generatesAndReturnsCorrelationIdWhenInboundHeaderIsMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> requestAttribute = new AtomicReference<>();
        AtomicReference<String> mdcValueInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            requestAttribute.set((String) request.getAttribute(TracingConstants.CORRELATION_ID_REQUEST_ATTRIBUTE));
            mdcValueInsideChain.set(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY));
        });

        assertThat(UUID.fromString(requestAttribute.get())).isNotNull();
        assertThat(mdcValueInsideChain.get()).isEqualTo(requestAttribute.get());
        assertThat(response.getHeader(TracingConstants.CORRELATION_ID_HEADER)).isEqualTo(requestAttribute.get());
        assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)).isNull();
    }

    @Test
    void restoresPreviousMdcContextAfterRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TracingConstants.CORRELATION_ID_HEADER, "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        MDC.put(TracingConstants.RUN_ID_MDC_KEY, "run-001");

        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)).isEqualTo("trace-123");
            assertThat(MDC.get(TracingConstants.RUN_ID_MDC_KEY)).isEqualTo("run-001");
        });

        assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)).isNull();
        assertThat(MDC.get(TracingConstants.RUN_ID_MDC_KEY)).isEqualTo("run-001");
        MDC.clear();
    }

    @Test
    void registersCorrelationIdFilterAtHighestPrecedenceOutsideSecurityChain() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TracingConfig.class)) {
            FilterRegistrationBean<?> registrationBean =
                    context.getBean("correlationIdFilterRegistration", FilterRegistrationBean.class);

            assertThat(registrationBean.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
            assertThat(registrationBean.getFilter()).isInstanceOf(CorrelationIdFilter.class);
        }
    }
}

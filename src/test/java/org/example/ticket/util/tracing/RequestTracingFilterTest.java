package org.example.ticket.util.tracing;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTracingFilterTest {

    private final RequestTracingFilter filter = new RequestTracingFilter();

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
        assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)).isNull();
    }

    @Test
    void generatesUuidWhenInboundCorrelationIdIsInvalid() throws ServletException, IOException {
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
        assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)).isNull();
    }

    @Test
    void registersTracingFilterAtHighestPrecedenceOutsideSecurityChain() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TracingConfig.class)) {
            FilterRegistrationBean<?> registrationBean =
                    context.getBean("requestTracingFilterRegistration", FilterRegistrationBean.class);

            assertThat(registrationBean.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
            assertThat(registrationBean.getFilter()).isInstanceOf(RequestTracingFilter.class);
        }
    }
}

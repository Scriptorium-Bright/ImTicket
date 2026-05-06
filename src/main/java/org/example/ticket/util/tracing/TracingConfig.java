package org.example.ticket.util.tracing;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class TracingConfig {

    @Bean
    public RequestTracingFilter requestTracingFilter() {
        return new RequestTracingFilter();
    }

    @Bean
    public FilterRegistrationBean<RequestTracingFilter> requestTracingFilterRegistration(
            RequestTracingFilter requestTracingFilter) {
        FilterRegistrationBean<RequestTracingFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(requestTracingFilter);
        registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registrationBean.addUrlPatterns("/*");
        return registrationBean;
    }
}

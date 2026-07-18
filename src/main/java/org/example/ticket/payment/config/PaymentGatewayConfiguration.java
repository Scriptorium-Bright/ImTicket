package org.example.ticket.payment.config;

import org.example.ticket.payment.gateway.FakePaymentGatewayClient;
import org.example.ticket.payment.gateway.PaymentGatewayClient;
import org.example.ticket.payment.gateway.PortOnePaymentGatewayClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(PortOneProperties.class)
public class PaymentGatewayConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "payment.gateway", name = "provider", havingValue = "fake", matchIfMissing = true)
    PaymentGatewayClient fakePaymentGatewayClient() {
        return new FakePaymentGatewayClient();
    }

    @Bean
    @ConditionalOnProperty(prefix = "payment.gateway", name = "provider", havingValue = "portone")
    PaymentGatewayClient portOnePaymentGatewayClient(RestClient.Builder restClientBuilder,
                                                      PortOneProperties portone) {
        if (!StringUtils.hasText(portone.getApiSecret())) {
            throw new IllegalStateException("PAYMENT_GATEWAY_PROVIDER=portone requires PORTONE_API_SECRET");
        }

        RestClient restClient = restClientBuilder
                .baseUrl(portone.getApiBaseUrl())
                .defaultHeader("Authorization", "PortOne " + portone.getApiSecret())
                .build();
        return new PortOnePaymentGatewayClient(restClient);
    }
}

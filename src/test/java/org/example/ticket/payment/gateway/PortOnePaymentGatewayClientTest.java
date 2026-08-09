package org.example.ticket.payment.gateway;

import org.example.ticket.payment.dto.PaymentAuthorization;
import org.example.ticket.payment.dto.VerifiedPaymentSnapshot;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.payment.exception.PaymentErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PortOnePaymentGatewayClientTest {

    @Test
    void verifiesOnlyPaidPaymentWhoseProviderDataMatchesTheServerOrder() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://portone.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PortOnePaymentGatewayClient gateway = new PortOnePaymentGatewayClient(
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "PortOne test-secret").build()
        );
        PaymentAuthorization authorization = authorization();

        server.expect(requestTo("https://portone.test/payments/imt-order-1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "PortOne test-secret"))
                .andRespond(withSuccess("""
                        {
                          "status":"PAID",
                          "id":"imt-order-1",
                          "transactionId":"portone-transaction-1",
                          "amount":{"total":45000},
                          "currency":"KRW",
                          "paidAt":"2026-07-18T08:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        VerifiedPaymentSnapshot snapshot = gateway.verify(authorization, "imt-order-1");

        assertThat(snapshot.merchantOrderId()).isEqualTo("imt-order-1");
        assertThat(snapshot.providerTransactionId()).isEqualTo("portone-transaction-1");
        assertThat(snapshot.approvedAmount()).isEqualTo(45000);
        assertThat(snapshot.approvedCurrency()).isEqualTo("KRW");
        server.verify();
    }

    @Test
    void rejectsPaymentThatIsNotPaid() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://portone.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PortOnePaymentGatewayClient gateway = new PortOnePaymentGatewayClient(builder.build());

        server.expect(requestTo("https://portone.test/payments/imt-order-1"))
                .andRespond(withSuccess("""
                        {
                          "status":"READY",
                          "id":"imt-order-1",
                          "amount":{"total":45000},
                          "currency":"KRW"
                        }
                        """, MediaType.APPLICATION_JSON));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> gateway.verify(authorization(), "imt-order-1"));

        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PROVIDER_REJECTED);
        server.verify();
    }

    @Test
    void rejectsProviderPaymentIdOrAmountMismatchBeforeReservationCompletion() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://portone.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PortOnePaymentGatewayClient gateway = new PortOnePaymentGatewayClient(builder.build());

        server.expect(requestTo("https://portone.test/payments/imt-order-1"))
                .andRespond(withSuccess("""
                        {
                          "status":"PAID",
                          "id":"imt-order-1",
                          "transactionId":"portone-transaction-1",
                          "amount":{"total":44000},
                          "currency":"KRW",
                          "paidAt":"2026-07-18T08:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> gateway.verify(authorization(), "imt-order-1"));

        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_DETAILS_MISMATCH);
        server.verify();
    }

    @Test
    void mapsPortOneHttpFailureToProviderRejectionWithoutLeakingTheResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://portone.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PortOnePaymentGatewayClient gateway = new PortOnePaymentGatewayClient(builder.build());

        server.expect(requestTo("https://portone.test/payments/imt-order-1"))
                .andRespond(withBadRequest());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> gateway.verify(authorization(), "imt-order-1"));

        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PROVIDER_REJECTED);
        server.verify();
    }

    private PaymentAuthorization authorization() {
        return new PaymentAuthorization(1L, 10L, "imt-order-1", 45000, "KRW");
    }
}

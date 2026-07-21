package org.example.ticket.common.exception;

import org.example.ticket.common.response.ApiResponse;
import org.example.ticket.reservation.exception.ReservationErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionUsesDomainErrorCode() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ReservationErrorCode.RESERVATION_EXPIRED)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode()).isEqualTo("RESERVATION_EXPIRED");
        assertThat(response.getBody().getError().getMessage()).isEqualTo("이미 만료된 좌석입니다. 처음부터 다시 진행해야 합니다.");
    }

    @Test
    void runtimeExceptionDoesNotExposeInternalMessage() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleRuntimeException(
                new RuntimeException("database password leaked")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().getError().getMessage()).isEqualTo("서버 내부 오류가 발생했습니다.");
    }

    @Test
    void seatLockTimeoutUsesTooManyRequests() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ReservationErrorCode.SEAT_LOCK_TIMEOUT)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode()).isEqualTo("SEAT_LOCK_TIMEOUT");
    }

    @Test
    void unsupportedHttpMethodUses405AndAllowHeader() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodNotAllowed(
                new HttpRequestMethodNotSupportedException("GET", java.util.List.of("POST"))
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).containsExactly(org.springframework.http.HttpMethod.POST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode()).isEqualTo("METHOD_NOT_ALLOWED");
    }
}

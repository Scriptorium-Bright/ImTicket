package org.example.ticket.payment.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.example.ticket.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {

    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key가 필요합니다."),
    PAYMENT_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND", "결제 주문을 찾을 수 없습니다."),
    PAYMENT_ORDER_NOT_OWNER(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_OWNER", "결제 주문을 찾을 수 없습니다."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "같은 멱등성 키로 다른 결제 요청을 보낼 수 없습니다."),
    PAYMENT_PROVIDER_REJECTED(HttpStatus.BAD_REQUEST, "PAYMENT_PROVIDER_REJECTED", "결제 승인을 검증할 수 없습니다."),
    PAYMENT_DETAILS_MISMATCH(HttpStatus.CONFLICT, "PAYMENT_DETAILS_MISMATCH", "결제 주문과 승인 정보가 일치하지 않습니다."),
    PAYMENT_ALREADY_FAILED(HttpStatus.CONFLICT, "PAYMENT_ALREADY_FAILED", "실패한 결제 주문은 다시 확정할 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}

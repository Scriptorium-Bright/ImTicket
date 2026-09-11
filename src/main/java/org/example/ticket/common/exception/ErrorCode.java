package org.example.ticket.common.exception;

import org.springframework.http.HttpStatus;

import java.util.OptionalLong;

public interface ErrorCode {

    HttpStatus status();

    String code();

    String message();

    /** 클라이언트가 재시도 전에 기다릴 초를 제공하며 기본값은 미지정이다. */
    default OptionalLong retryAfterSeconds() {
        return OptionalLong.empty();
    }
}

package org.example.ticket.common.exception;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.common.response.ApiResponse;
import org.example.ticket.common.response.ErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.example.ticket.reservation.booking.domain.ReservationErrorCode;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("BusinessException: code={}, message={}", errorCode.code(), errorCode.message());
        return toResponse(errorCode);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleEntityNotFoundException(EntityNotFoundException e) {
        log.warn("EntityNotFoundException: {}", e.getMessage());
        return toResponse(CommonErrorCode.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(EntityExistsException.class)
    public ResponseEntity<ApiResponse<Void>> handleEntityExistsException(EntityExistsException e) {
        log.warn("EntityExistsException: {}", e.getMessage());
        return toResponse(CommonErrorCode.CONFLICT, e.getMessage());
    }

    @ExceptionHandler({OptimisticLockException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLockException(RuntimeException e) {
        log.warn("Optimistic lock conflict: {}", e.getMessage());
        return toResponse(ReservationErrorCode.SEAT_ALREADY_RESERVED);
    }

    @ExceptionHandler({
            PessimisticLockingFailureException.class,
            jakarta.persistence.PessimisticLockException.class,
            jakarta.persistence.LockTimeoutException.class
    })
    public ResponseEntity<ApiResponse<Void>> handlePessimisticLockException(RuntimeException e) {
        log.warn("Pessimistic lock acquisition failed: {}", e.getMessage());
        return toResponse(ReservationErrorCode.SEAT_LOCK_TIMEOUT);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("IllegalArgumentException: {}", e.getMessage());
        return toResponse(CommonErrorCode.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalStateException(IllegalStateException e) {
        log.warn("IllegalStateException: {}", e.getMessage());
        return toResponse(CommonErrorCode.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        BindingResult bindingResult = e.getBindingResult();
        String errorMessage = bindingResult.getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining(", "));

        log.warn("ValidationException: {}", errorMessage);
        return toResponse(CommonErrorCode.VALIDATION_ERROR, errorMessage);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        HttpHeaders headers = new HttpHeaders();
        if (e.getSupportedHttpMethods() != null) {
            headers.setAllow(e.getSupportedHttpMethods());
        }
        log.warn("Method not allowed: method={}, supported={}", e.getMethod(), e.getSupportedHttpMethods());
        return ResponseEntity.status(CommonErrorCode.METHOD_NOT_ALLOWED.status())
                .headers(headers)
                .body(ApiResponse.fail(ErrorResponse.of(
                        CommonErrorCode.METHOD_NOT_ALLOWED.code(),
                        CommonErrorCode.METHOD_NOT_ALLOWED.message()
                )));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(RuntimeException e) {
        log.error("RuntimeException: ", e);
        return toResponse(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Exception: ", e);
        return toResponse(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode errorCode) {
        return toResponse(errorCode, errorCode.message());
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.status())
                .body(ApiResponse.fail(ErrorResponse.of(errorCode.code(), message)));
    }
}

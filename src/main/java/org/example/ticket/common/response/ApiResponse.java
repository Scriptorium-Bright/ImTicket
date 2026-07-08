package org.example.ticket.common.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiResponse<T> {

    private boolean success;
    private T data;
    private ErrorResponse error;

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .error(null)
                .build();
    }

    public static <T> ApiResponse<T> success() {
        return ApiResponse.<T>builder()
                .success(true)
                .data(null)
                .error(null)
                .build();
    }

    public static <T> ApiResponse<T> fail(ErrorResponse errorResponse) {
        return ApiResponse.<T>builder()
                .success(false)
                .data(null)
                .error(errorResponse)
                .build();
    }
}

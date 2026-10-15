package com.meowstar.careers.error;

import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * 全应用唯一错误类型（对齐 Rust 版 {@code AppError}）：
 * 处理器抛出它，由 {@link ApiErrorHandler} 统一转成 {@code {code, message}} JSON 并记录日志；
 * 5xx 一律对客户端脱敏。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", message);
    }

    /** 形如 {@code notFound("user `" + id + "`")} -> “user `xxx` not found”。 */
    public static ApiException notFoundResource(String resource) {
        return notFound(resource + " not found");
    }

    public static ApiException notFoundUser(UUID id) {
        return notFound("user `" + id + "` not found");
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "conflict", message);
    }

    public static ApiException serviceUnavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable", message);
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests", message);
    }

    public static ApiException internal(String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", message);
    }
}

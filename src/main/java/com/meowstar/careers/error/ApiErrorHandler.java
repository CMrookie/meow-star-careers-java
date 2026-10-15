package com.meowstar.careers.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一错误出口：所有异常在此转成 {@code {code, message}} JSON 并集中记录日志。
 * <ul>
 *   <li>5xx 对客户端脱敏（internal server error / service unavailable），原始错误只进日志；</li>
 *   <li>唯一约束冲突 -> 409 conflict（对齐 sqlx 的唯一约束错误转换）；</li>
 *   <li>非法 JSON / 类型不符 / 缺参数 -> 400 bad_request；</li>
 *   <li>未匹配路由 -> 404 not_found（“route not found”）。</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);
    private static final String BAD_JSON = "malformed or invalid JSON request body";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        return logAndBuild(ex.status(), ex.code(), ex.getMessage(), ex, request);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateKey(DuplicateKeyException ex, HttpServletRequest request) {
        return logAndBuild(HttpStatus.CONFLICT, "conflict",
                "a record with the same unique key already exists", ex, request);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException ex, HttpServletRequest request) {
        return logAndBuild(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "internal server error", ex, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("json payload rejected: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse("bad_request", BAD_JSON));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ErrorResponse> handleBinding(Exception ex, HttpServletRequest request) {
        String message = BAD_JSON;
        if (ex instanceof MethodArgumentNotValidException invalid && invalid.getBindingResult().hasFieldErrors()) {
            message = Objects.requireNonNull(invalid.getBindingResult().getFieldError()).getDefaultMessage();
        }
        return logAndBuild(HttpStatus.BAD_REQUEST, "bad_request", message, ex, request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleBadParameter(Exception ex, HttpServletRequest request) {
        return logAndBuild(HttpStatus.BAD_REQUEST, "bad_request", BAD_JSON, ex, request);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoHandler(NoHandlerFoundException ex, HttpServletRequest request) {
        return logAndBuild(HttpStatus.NOT_FOUND, "not_found", "route not found", ex, request);
    }

    /** 未匹配的静态资源请求（Spring 6.1+ 由资源处理器抛出）同样走统一 404。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex,
                                                          HttpServletRequest request) {
        return logAndBuild(HttpStatus.NOT_FOUND, "not_found", "route not found", ex, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                  HttpServletRequest request) {
        return logAndBuild(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "method not allowed", ex, request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex,
                                                         HttpServletRequest request) {
        return logAndBuild(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type",
                "unsupported media type", ex, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        return logAndBuild(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "internal server error", ex, request);
    }

    private ResponseEntity<ErrorResponse> logAndBuild(HttpStatus status, String code, String message,
                                                      Exception ex, HttpServletRequest request) {
        String clientMessage = switch (status) {
            case INTERNAL_SERVER_ERROR -> "internal server error";
            case SERVICE_UNAVAILABLE -> "service unavailable";
            default -> message;
        };
        if (status.is5xxServerError()) {
            log.error("request failed status={} code={} method={} path={} error={}",
                    status.value(), code, request.getMethod(), request.getRequestURI(), ex.toString(), ex);
        } else {
            log.warn("request rejected status={} code={} method={} path={} error={}",
                    status.value(), code, request.getMethod(), request.getRequestURI(), ex.toString());
        }
        return ResponseEntity.status(status).body(new ErrorResponse(code, clientMessage));
    }
}

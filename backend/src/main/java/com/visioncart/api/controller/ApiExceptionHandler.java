package com.visioncart.api.controller;

import com.visioncart.api.dto.ApiResponse;
import com.visioncart.service.recognition.ImageQualityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> validation(MethodArgumentNotValidException error) {
        String details = error.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ApiResponse.fail(400, "请求参数不完整: " + details);
    }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> unauthorized(SecurityException error) {
        log.warn("Security exception: {}", error.getMessage()); // Bug #27
        return ApiResponse.fail(401, error.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> authentication(AuthenticationException error) {
        log.warn("Authentication failed: {}", error.getMessage());
        return ApiResponse.fail(401, "认证失败，请重新登录");
    }

    @ExceptionHandler(ImageQualityException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> imageQuality(ImageQualityException error) {
        return ApiResponse.fail(400, error.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> badRequest(IllegalArgumentException error) {
        return ApiResponse.fail(400, error.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiResponse<Void> accessDenied(AccessDeniedException error) {
        log.warn("Access denied: {}", error.getMessage()); // Bug #27
        return ApiResponse.fail(403, "无权限访问");
    }

    // --- Bug #26: Missing Spring exception handlers ---

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    ApiResponse<Void> methodNotAllowed(HttpRequestMethodNotSupportedException error) {
        return ApiResponse.fail(405, "不支持的请求方法: " + error.getMethod());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    ApiResponse<Void> unsupportedMediaType(HttpMediaTypeNotSupportedException error) {
        return ApiResponse.fail(415, "不支持的媒体类型: " + error.getContentType());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> messageNotReadable(HttpMessageNotReadableException error) {
        return ApiResponse.fail(400, "请求体格式错误，请检查JSON格式");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> missingParameter(MissingServletRequestParameterException error) {
        return ApiResponse.fail(400, "缺少必要参数: " + error.getParameterName());
    }

    // --- Redis exception handler ---

    @ExceptionHandler(org.springframework.data.redis.RedisConnectionFailureException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> redisUnavailable(org.springframework.data.redis.RedisConnectionFailureException error) {
        log.warn("Redis unavailable, non-critical feature degraded: {}", error.getMessage());
        return ApiResponse.fail(503, "缓存服务暂时不可用，部分功能可能较慢");
    }

    // --- Database exception handlers (specific → generic) ---

    @ExceptionHandler(CannotGetJdbcConnectionException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> dbConnection(CannotGetJdbcConnectionException error) {
        log.error("Database connection unavailable", error);
        return ApiResponse.fail(503, "服务繁忙，数据库连接不可用，请稍后重试");
    }

    @ExceptionHandler(QueryTimeoutException.class)
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    ApiResponse<Void> queryTimeout(QueryTimeoutException error) {
        log.error("Database query timeout", error);
        return ApiResponse.fail(504, "查询超时，请稍后重试");
    }

    @ExceptionHandler(TransientDataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> transientDb(TransientDataAccessException error) {
        log.error("Transient database error", error);
        return ApiResponse.fail(503, "数据库暂时不可用，请稍后重试");
    }

    @ExceptionHandler(RejectedExecutionException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> queueFull(RejectedExecutionException error) {
        log.warn("Request rejected because executor queue is full: {}", error.getMessage());
        return ApiResponse.fail(503, "服务繁忙，请稍后重试");
    }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> dataAccess(DataAccessException error) {
        log.error("Database error", error);
        return ApiResponse.fail(500, "服务器内部错误");
    }

    // --- Catch-all with correlation ID (Bug #40) ---

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> generic(Exception error) {
        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        log.error("[{}] Unhandled exception", correlationId, error);
        return ApiResponse.fail(500, "服务器内部错误 (ref: " + correlationId + ")");
    }
}

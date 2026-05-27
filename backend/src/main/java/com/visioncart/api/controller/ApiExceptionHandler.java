package com.visioncart.api.controller;

import com.visioncart.api.dto.ApiResponse;
import com.visioncart.service.recognition.ImageQualityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
        return ApiResponse.fail(401, error.getMessage());
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
        return ApiResponse.fail(403, "无权限访问");
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

    // --- Catch-all ---

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> generic(Exception error) {
        log.error("Unhandled exception", error);
        return ApiResponse.fail(500, "服务器内部错误");
    }
}

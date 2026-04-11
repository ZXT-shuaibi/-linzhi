package com.zhiguang.be.auth.exception;

import com.zhiguang.be.common.api.ErrorResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 认证授权模块异常处理器。
 */
@RestControllerAdvice
@Order(1)
public class AuthExceptionHandler {

    /**
     * 处理认证授权业务异常。
     */
    @ExceptionHandler(AuthBusinessException.class)
    public ResponseEntity<ErrorResponse> handleAuthBusinessException(AuthBusinessException ex) {
        ErrorResponse body = ErrorResponse.of(ex.errorCode().code(), ex.getMessage(), List.of());
        return ResponseEntity.status(ex.httpStatus()).body(body);
    }
}

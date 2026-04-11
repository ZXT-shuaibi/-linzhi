package com.zhiguang.be.auth.exception;

import org.springframework.http.HttpStatus;

/**
 * 认证授权模块业务异常。
 */
public class AuthBusinessException extends RuntimeException {
    private final AuthErrorCode errorCode;
    private final HttpStatus httpStatus;

    /**
     * 构造函数。
     */
    public AuthBusinessException(AuthErrorCode errorCode, HttpStatus httpStatus) {
        this(errorCode, httpStatus, errorCode.defaultMessage());
    }

    /**
     * 构造函数。
     */
    public AuthBusinessException(AuthErrorCode errorCode, HttpStatus httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    /**
     * 获取错误码。
     */
    public AuthErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 获取HTTP状态码。
     */
    public HttpStatus httpStatus() {
        return httpStatus;
    }
}

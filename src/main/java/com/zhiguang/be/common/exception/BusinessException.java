package com.zhiguang.be.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 类说明。
 */
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final HttpStatus httpStatus;

    /**
     * 方法说明。
     */
    public BusinessException(ErrorCode errorCode, HttpStatus httpStatus) {
        this(errorCode, httpStatus, errorCode.defaultMessage());
    }

    /**
     * 方法说明。
     */
    public BusinessException(ErrorCode errorCode, HttpStatus httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    /**
     * 方法说明。
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 方法说明。
     */
    public HttpStatus httpStatus() {
        return httpStatus;
    }
}


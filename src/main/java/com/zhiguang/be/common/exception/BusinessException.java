package com.zhiguang.be.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常类型。
 * 用于承载明确可预期的业务失败场景，并附带错误码和 HTTP 状态码。
 */
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final HttpStatus httpStatus;

    /**
     * 使用错误码默认消息构造业务异常。
     *
     * @param errorCode 错误码枚举
     * @param httpStatus HTTP 状态码
     */
    public BusinessException(ErrorCode errorCode, HttpStatus httpStatus) {
        this(errorCode, httpStatus, errorCode.defaultMessage());
    }

    /**
     * 使用自定义消息构造业务异常。
     *
     * @param errorCode 错误码枚举
     * @param httpStatus HTTP 状态码
     * @param message 自定义错误消息
     */
    public BusinessException(ErrorCode errorCode, HttpStatus httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    /**
     * 获取业务错误码。
     *
     * @return 错误码枚举
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 获取要返回给客户端的 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
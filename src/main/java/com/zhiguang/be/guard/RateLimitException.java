package com.zhiguang.be.guard;

import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 限流异常。
 * 统一复用全局业务异常处理器输出 429 响应。
 */
public class RateLimitException extends BusinessException {

    /**
     * 构造限流异常。
     */
    public RateLimitException(String message) {
        super(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, message);
    }
}

package com.zhiguang.be.common.api;

import com.zhiguang.be.common.util.RequestIdUtil;

import java.time.Instant;
import java.util.List;

/**
 * 数据结构说明。
 */
public record ErrorResponse(
        String code,
        String message,
        List<ApiFieldError> errors,
        String requestId,
        Instant timestamp
) {
    /**
     * 方法说明。
     */
    public static ErrorResponse of(String code, String message, List<ApiFieldError> errors) {
        return new ErrorResponse(code, message, errors, RequestIdUtil.currentOrNew(), Instant.now());
    }
}


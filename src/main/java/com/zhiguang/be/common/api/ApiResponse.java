package com.zhiguang.be.common.api;

import com.zhiguang.be.common.util.RequestIdUtil;

import java.time.Instant;

/**
 * 数据结构说明。
 */
public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String requestId,
        Instant timestamp
) {
    /**
     * 方法说明。
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                "OK",
                "success",
                data,
                RequestIdUtil.currentOrNew(),
                Instant.now()
        );
    }
}


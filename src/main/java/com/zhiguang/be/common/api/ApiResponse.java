package com.zhiguang.be.common.api;

import com.zhiguang.be.common.util.RequestIdUtil;

import java.time.Instant;

/**
 * API 统一成功响应结构。
 * 用于封装业务成功返回时的标准字段，保证接口输出格式一致。
 *
 * @param <T> 业务数据类型
 * @param code 响应码，成功场景固定为 {@code OK}
 * @param message 响应消息，成功场景固定为 {@code success}
 * @param data 业务数据载荷
 * @param requestId 请求追踪标识
 * @param timestamp 响应生成时间
 */
public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String requestId,
        Instant timestamp
) {
    /**
     * 构造标准成功响应。
     * 自动填充成功状态码、固定消息、请求 ID 和当前时间戳。
     *
     * @param <T> 业务数据类型
     * @param data 要返回的业务数据
     * @return 标准化成功响应对象
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
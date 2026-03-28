package com.zhiguang.be.common.util;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 请求 ID 工具类。
 * 用于从日志上下文中获取请求标识，保证响应和日志链路都能拿到统一追踪号。
 */
public final class RequestIdUtil {

    /**
     * 禁止实例化工具类。
     */
    private RequestIdUtil() {
    }

    /**
     * 获取当前请求的请求 ID。
     * 如果日志上下文中不存在 requestId，则回退生成一个新的 UUID。
     *
     * @return 当前请求 ID 或新生成的 UUID
     */
    public static String currentOrNew() {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return requestId;
    }
}
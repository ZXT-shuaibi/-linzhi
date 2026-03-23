package com.zhiguang.be.common.util;

import org.slf4j.MDC;

import java.util.UUID;

public final class RequestIdUtil {

    /**
     * 方法说明。
     */
    private RequestIdUtil() {
    }

    /**
     * 方法说明。
     */
    public static String currentOrNew() {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return requestId;
    }
}


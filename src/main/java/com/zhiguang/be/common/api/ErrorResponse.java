package com.zhiguang.be.common.api;

import com.zhiguang.be.common.util.RequestIdUtil;

import java.time.Instant;
import java.util.List;

/**
 * API 统一错误响应结构。
 * 用于封装业务异常、校验异常和系统异常等失败场景的标准返回体。
 *
 * @param code 错误码
 * @param message 错误描述
 * @param errors 字段级错误明细
 * @param requestId 请求追踪标识
 * @param timestamp 响应生成时间
 */
public record ErrorResponse(
        String code,
        String message,
        List<ApiFieldError> errors,
        String requestId,
        Instant timestamp
) {
    /**
     * 构造标准错误响应。
     * 自动填充请求 ID 和时间戳，减少异常处理处的重复代码。
     *
     * @param code 错误码
     * @param message 错误描述
     * @param errors 字段级错误列表
     * @return 标准化错误响应对象
     */
    public static ErrorResponse of(String code, String message, List<ApiFieldError> errors) {
        return new ErrorResponse(code, message, errors, RequestIdUtil.currentOrNew(), Instant.now());
    }
}
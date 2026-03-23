package com.zhiguang.be.auth.model;

/**
 * 数据结构说明。
 */
public record ActionResult(
        boolean success,
        String action,
        String resourceId,
        String status
) {
}


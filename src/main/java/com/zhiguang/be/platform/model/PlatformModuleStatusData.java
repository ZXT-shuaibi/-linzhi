package com.zhiguang.be.platform.model;

/**
 * 平台模块状态。
 */
public record PlatformModuleStatusData(
        String module,
        String status,
        String note
) {
}

package com.zhiguang.be.platform.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 热点 Key 重置请求。
 */
public record PlatformHotKeyResetRequest(
        @NotBlank(message = "热点 key 不能为空")
        String key
) {
}

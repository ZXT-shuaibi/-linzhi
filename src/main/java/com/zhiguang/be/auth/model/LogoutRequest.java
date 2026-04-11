package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 登出请求。
 *
 * @param refreshToken 当前会话的刷新令牌
 * @param logoutScope 登出范围，当前项目主要使用 current_device
 */
public record LogoutRequest(
        @NotBlank(message = "刷新令牌不能为空") String refreshToken,
        @Pattern(regexp = "current_device|all_devices", message = "登出范围不合法") String logoutScope
) {
}


package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 数据结构说明。
 */
public record LogoutRequest(
        @NotBlank String refreshToken,
        @Pattern(regexp = "current_device|all_devices") String logoutScope
) {
}


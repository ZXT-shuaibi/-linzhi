package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 数据结构说明。
 */
public record PasswordResetRequest(
        @NotBlank String phone,
        @NotBlank String smsCode,
        @NotBlank @Size(min = 8, max = 128) String newPassword
) {
}


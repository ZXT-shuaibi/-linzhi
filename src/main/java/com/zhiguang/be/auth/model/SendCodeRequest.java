package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 发送验证码请求。
 */
public record SendCodeRequest(
        @NotBlank @Pattern(regexp = "^1\\d{10}$", message = "phone must be a valid mobile number") String phone,
        @NotNull CodeScene scene
) {
}

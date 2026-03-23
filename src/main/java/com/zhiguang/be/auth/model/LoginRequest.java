package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 */
public record LoginRequest(
        @NotBlank @Pattern(regexp = "^1\\d{10}$", message = "identifier must be a valid mobile number") String identifier,
        @NotBlank @Size(min = 8, max = 128) String password,
        @Size(max = 32) String channel,
        @NotBlank @Size(min = 16, max = 2048) String captchaToken
) {
}
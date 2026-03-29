package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 */
public record LoginRequest(
        @NotBlank @Size(min = 3, max = 32) String identifier,
        @NotBlank @Size(min = 8, max = 128) String password,
        @Size(max = 32) String channel,
        @Size(min = 16, max = 2048) String captchaToken
) {
}
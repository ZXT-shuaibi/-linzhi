package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 * 支持两种登录方式：密码登录（identifier + password）和验证码登录（identifier + smsCode）。
 */
public record LoginRequest(
        @NotBlank @Size(min = 3, max = 32) String identifier,
        @Size(min = 8, max = 128) String password,
        @Size(min = 6, max = 6) String smsCode,
        @Size(max = 32) String channel,
        @Size(min = 16, max = 2048) String captchaToken
) {
}
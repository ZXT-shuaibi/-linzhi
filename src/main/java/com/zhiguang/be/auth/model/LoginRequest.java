package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 数据结构说明。
 */
public record LoginRequest(
        @NotBlank String identifier,
        @NotBlank @Size(min = 8, max = 128) String password,
        String channel,
        String captchaToken
) {
}


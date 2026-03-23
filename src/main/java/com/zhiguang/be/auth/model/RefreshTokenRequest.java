package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 数据结构说明。
 */
public record RefreshTokenRequest(@NotBlank String refreshToken) {
}


package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 数据结构说明。
 */
public record RegisterRequest(
        @NotBlank String phone,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(max = 64) String nickname,
        @NotBlank String smsCode
) {
}


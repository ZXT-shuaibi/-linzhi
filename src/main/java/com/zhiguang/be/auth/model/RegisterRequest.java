package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 */
public record RegisterRequest(
        @NotBlank @Pattern(regexp = "^1\\d{10}$", message = "phone must be a valid mobile number") String phone,
        @NotBlank @Size(min = 3, max = 32) String username,
        @NotBlank @Size(min = 8, max = 128) @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "password must contain letters and numbers") String password,
        @NotBlank @Size(max = 64) String nickname,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "smsCode must be 6 digits") String smsCode
) {
}
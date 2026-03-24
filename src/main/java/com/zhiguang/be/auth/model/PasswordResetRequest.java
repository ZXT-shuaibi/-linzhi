package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 密码重置请求。
 */
public record PasswordResetRequest(
        @NotBlank @Pattern(regexp = "^1\\d{10}$", message = "phone must be a valid mobile number") String phone,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "smsCode must be 6 digits") String smsCode,
        @NotBlank @Size(min = 8, max = 128) @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "password must contain letters and numbers") String newPassword
) {
}
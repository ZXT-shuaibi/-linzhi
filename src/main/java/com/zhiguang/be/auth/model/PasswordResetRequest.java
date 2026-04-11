package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 重置密码请求。
 *
 * @param phone 手机号
 * @param smsCode 短信验证码
 * @param newPassword 新密码
 */
public record PasswordResetRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        String phone,
        @NotBlank(message = "验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码必须为 6 位数字")
        String smsCode,
        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 128, message = "新密码长度必须在 8 到 128 位之间")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "新密码必须同时包含字母和数字")
        String newPassword
) {
}

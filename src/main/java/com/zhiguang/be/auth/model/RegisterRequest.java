package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 * 当前流程要求手机号、账号、密码、昵称和短信验证码一次性提交。
 *
 * @param phone 手机号
 * @param account 登录账号
 * @param password 密码
 * @param nickname 昵称
 * @param smsCode 短信验证码
 */
public record RegisterRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        String phone,
        @NotBlank(message = "账号不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_]{4,32}$", message = "账号必须为 4 到 32 位字母、数字或下划线")
        String account,
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 128, message = "密码长度必须在 8 到 128 位之间")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "密码必须同时包含字母和数字")
        String password,
        @NotBlank(message = "昵称不能为空")
        @Size(max = 64, message = "昵称长度不能超过 64 位")
        String nickname,
        @NotBlank(message = "验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码必须为 6 位数字")
        String smsCode
) {
}

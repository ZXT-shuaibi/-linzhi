package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 * 当前流程要求手机号、短信验证码、昵称、密码和二次确认密码。
 * 登录账号不再由用户输入，服务端会使用手机号作为内部账号。
 *
 * @param phone 手机号
 * @param password 密码
 * @param confirmPassword 确认密码
 * @param nickname 昵称
 * @param smsCode 短信验证码
 */
public record RegisterRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        String phone,
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 128, message = "密码长度必须在 8 到 128 位之间")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "密码必须同时包含字母和数字")
        String password,
        @NotBlank(message = "确认密码不能为空")
        @Size(min = 8, max = 128, message = "确认密码长度必须在 8 到 128 位之间")
        String confirmPassword,
        @NotBlank(message = "昵称不能为空")
        @Size(max = 64, message = "昵称长度不能超过 64 位")
        String nickname,
        @NotBlank(message = "验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码必须为 6 位数字")
        String smsCode
) {
}
